package org.intellij.flux;

import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.flux.core.*;
import org.eclipse.flux.core.internal.messaging.SocketIOMessagingConnector;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by Maxim.Mossienko on 6/6/2014.
 */
public class FluxApplicationComponent implements ApplicationComponent {
    private LiveEditCoordinator liveEditCoordinator;
    private IMessagingConnector messagingConnector;
    private Repository repository;

    private static final String GET_LIVE_RESOURCES_CALLBACK = "LiveEditUnits - getLiveResourcesCallback";
    private static final String LIVE_EDIT_CONNECTOR_ID = "Idea-Service-Live-Edit-Connector";
    private static final Key<Boolean> ourChangeFlag = Key.create("our.change");

    @Override
    public void initComponent() {
        String username = System.getProperty("flux-username", "defaultuser");
        // TODO: change this username property to a preference and add authentication
        messagingConnector = new SocketIOMessagingConnector(username);
        liveEditCoordinator = new LiveEditCoordinator(messagingConnector);
        repository = new Repository(messagingConnector, username);

        final ConcurrentMap<String, Object> liveEditUnits = new ConcurrentHashMap<>();

        messagingConnector.addMessageHandler(new CallbackIDAwareMessageHandler("getLiveResourcesResponse", GET_LIVE_RESOURCES_CALLBACK.hashCode()) {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                startupLiveUnits(message);
            }
        });

        liveEditCoordinator.addLiveEditConnector(new ILiveEditConnector() {
            @Override
            public String getConnectorID() {
                return LIVE_EDIT_CONNECTOR_ID;
            }

            @Override
            public void liveEditingStarted(String requestSenderID, int callbackID, String username, String resourcePath, String hash, long timestamp) {
                startLiveUnit(requestSenderID, callbackID, username, resourcePath, hash, timestamp);
            }

            @Override
            public void liveEditingStartedResponse(String requestSenderID, int callbackID, String username, String projectName, String resourcePath, String savePointHash, long savePointTimestamp, String content) {
                if (repository.getUsername().equals(username) /* && resourcePath.endsWith(".java") && repository.isConnected(projectName)*/) {
                    VirtualFile referencedFile = Utils.findReferencedFile(resourcePath, projectName);
                    if (referencedFile == null) return;
                    Document document = FileDocumentManager.getInstance().getDocument(referencedFile);
                    if (document != null) {
                        String liveContent = document.getText();
                        String liveUnitHash = DigestUtils.shaHex(liveContent);

                        String remoteContentHash = DigestUtils.shaHex(content);
                        if (!liveUnitHash.equals(remoteContentHash)) {
                            ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
                                document.putUserData(ourChangeFlag, Boolean.TRUE);
                                try {
                                    document.setText(content);
                                } finally {
                                    document.putUserData(ourChangeFlag, null);
                                }

                            }));
                        }
                    }
                }
            }

            @Override
            public void liveEditingEvent(String username, String resourcePath, final int offset, final int removeCount, final String newText) {
                if (repository.getUsername().equals(username) /*&& liveEditUnits.containsKey(resourcePath)*/) {
                    String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
                    String relativeResourcePath = resourcePath.substring(projectName.length() + 1);
                    final VirtualFile referencedFile = Utils.findReferencedFile(relativeResourcePath, projectName);
                    if (referencedFile != null) {

                        Runnable runnable = () -> {
                            Document document = FileDocumentManager.getInstance().getDocument(referencedFile);
                            if (document != null) {
                                CommandProcessor.getInstance().executeCommand(() -> {
                                    document.putUserData(ourChangeFlag, Boolean.TRUE);
                                    try {
                                        document.replaceString(offset, offset + removeCount, newText);
                                    } finally {
                                        document.putUserData(ourChangeFlag, null);
                                    }
                                }, "Edit", null);
                            }
                        };

                        ApplicationManager.getApplication().invokeLater(
                                () -> ApplicationManager.getApplication().runWriteAction(runnable)
                        );
                    }
                }
            }
        });

        this.repository.addRepositoryListener(new IRepositoryListener() {
            @Override
            public void projectConnected(Project project) {
                startupConnectedProject(project);
            }
            @Override
            public void projectDisconnected(Project project) {
            }
        });

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(DocumentEvent documentEvent) {
                try {
                    Document document = documentEvent.getDocument();
                    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
                    if (virtualFile == null) return;

                    Project referencedProject = Utils.findReferencedProject(virtualFile);
                    // todo several projects
                    if (referencedProject == null) return;
                    String resourcePath = VfsUtil.getRelativePath(virtualFile, referencedProject.getBaseDir());
                    if (resourcePath == null) return;

                    if (document.getUserData(ourChangeFlag) == null) {
                        long changeTimestamp = document.getModificationStamp(); // todo
                        //                    if (changeTimestamp > connectedProject.getTimestamp(resourcePath)) {
                        String changeHash = DigestUtils.shaHex(document.getText());
                        //                        if (!changeHash.equals(connectedProject.getHash(resourcePath))) {
                        //
                        ////                        connectedProject.setTimestamp(resourcePath, changeTimestamp);
                        ////                        connectedProject.setHash(resourcePath, changeHash);

                        getLiveEditCoordinator().sendModelChangedMessage(LIVE_EDIT_CONNECTOR_ID, username,
                                referencedProject.getName(),
                                resourcePath, documentEvent.getOffset(), documentEvent.getOldLength(),
                                documentEvent.getNewFragment().toString());
                        //                        }
                        //                    }
                    }

                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            com.intellij.openapi.application.AccessToken accessToken = WriteAction.start();
                            PsiDocumentManager.getInstance(referencedProject).commitDocument(document);
                            accessToken.finish();
                            errorAnalyzerService.sendProblems(username, LIVE_EDIT_CONNECTOR_ID.hashCode(), "sender", referencedProject.getName(), resourcePath, "liveMetadataChanged");

                        } catch (JSONException ex) {
                            ex.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        new NavigationService(messagingConnector, repository);
        new ContentAssistService(messagingConnector, repository);
        errorAnalyzerService = new ErrorAnalyzerService(messagingConnector, repository);
        new RenameService(messagingConnector, repository);
    }

    private ErrorAnalyzerService errorAnalyzerService;

    @Override
    public void disposeComponent() {

    }

    @NotNull
    @Override
    public String getComponentName() {
        return "FluxConnector";
    }

    public IMessagingConnector getMessagingConnector() {
        return messagingConnector;
    }
    public LiveEditCoordinator getLiveEditCoordinator() {
        return liveEditCoordinator;
    }

    protected void startupConnectedProject(Project project) {
        try {
            JSONObject message = new JSONObject();
            message.put("username", repository.getUsername());
            message.put("project", project.getName());
            message.put("callback_id", GET_LIVE_RESOURCES_CALLBACK);
            messagingConnector.send("getLiveResourcesRequest", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    protected void startupLiveUnits(JSONObject message) {
        try {
            JSONArray liveUnits = message.getJSONArray("liveEditUnits");
            for (int i = 0; i < liveUnits.length(); i++) {
                JSONObject liveUnit = liveUnits.getJSONObject(i);

                String username = liveUnit.getString("username");
                String projectName = liveUnit.getString("project");
                String resource = liveUnit.getString("resource");
                long timestamp =  liveUnit.getLong("savePointTimestamp");
                String hash = liveUnit.getString("savePointHash");

                String resourcePath = projectName + "/" + resource;
                if (repository.getUsername().equals(username) /*&& !liveEditUnits.containsKey(resourcePath)*/) {
                    startLiveUnit(null, 0, username, resourcePath, hash, timestamp);
                }

                this.liveEditCoordinator.sendLiveEditStartedMessage(LIVE_EDIT_CONNECTOR_ID, username, projectName, resource, hash, timestamp);
            }
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }

    protected void startLiveUnit(String requestSenderID, int callbackID, String username, String resourcePath, String hash, long timestamp) {
        if (repository.getUsername().equals(username) /*&& resourcePath.endsWith(".java")*/) {

            String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
            String relativeResourcePath = resourcePath.substring(projectName.length() + 1);

            AccessToken accessToken = ReadAction.start();
            try {
                VirtualFile referencedFile = Utils.findReferencedFile(relativeResourcePath, projectName);
                if (referencedFile != null) {
                    Document document = FileDocumentManager.getInstance().getDocument(referencedFile);
                    if (document != null) {
                        String liveContent = document.getText();
                        String liveUnitHash = DigestUtils.shaHex(liveContent);
                        if (!liveUnitHash.equals(hash)) {
                            liveEditCoordinator.sendLiveEditStartedResponse(LIVE_EDIT_CONNECTOR_ID, requestSenderID, callbackID, username, projectName, relativeResourcePath, hash, timestamp, liveContent);
                        }

                        try {
                            errorAnalyzerService.sendProblems(username, callbackID, "sender", projectName, relativeResourcePath, "liveMetadataChanged");
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            } finally {
                accessToken.finish();
            }
        }
    }
}
