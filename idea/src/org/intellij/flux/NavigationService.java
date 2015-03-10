package org.intellij.flux;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.eclipse.flux.core.AbstractMessageHandler;
import org.eclipse.flux.core.IMessageHandler;
import org.eclipse.flux.core.IMessagingConnector;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by maximmossienko on 20/02/15.
 */
public class NavigationService {
    private final IMessagingConnector messagingConnector;
    private final Repository repository;

    public NavigationService(IMessagingConnector messagingConnector, Repository repository) {
        this.messagingConnector = messagingConnector;
        this.repository = repository;

        IMessageHandler navigationRequestHandler = new AbstractMessageHandler("navigationrequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                handleNavigationRequest(message);
            }
        };
        messagingConnector.addMessageHandler(navigationRequestHandler);
    }

    protected void handleNavigationRequest(JSONObject message) {
        try {
            String username = message.getString("username");
            String projectName = message.getString("project");
            String resourcePath = message.getString("resource");
            int callbackID = message.getInt("callback_id");

            String liveEditID = projectName + "/" + resourcePath;
            if (repository.getUsername().equals(username) /*liveEditUnits.isLiveEditResource(username, liveEditID)*/) {

                int offset = message.getInt("offset");
                int length = message.getInt("length");
                String sender = message.getString("requestSenderID");

                JSONObject navigationResult = computeNavigation(username, liveEditID, offset, length);

                if (navigationResult != null) {
                    JSONObject responseMessage = new JSONObject();
                    responseMessage.put("username", username);
                    responseMessage.put("project", projectName);
                    responseMessage.put("resource", resourcePath);
                    responseMessage.put("callback_id", callbackID);
                    responseMessage.put("requestSenderID", sender);
                    responseMessage.put("navigation", navigationResult);

                    messagingConnector.send("navigationresponse", responseMessage);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private JSONObject computeNavigation(String username, String requestorResourcePath, int offset, int length) {
        try {
            if (repository.getUsername().equals(username) /*&& liveEditUnits.containsKey(resourcePath)*/) {
                String projectName = requestorResourcePath.substring(0, requestorResourcePath.indexOf('/'));
                String relativeResourcePath = requestorResourcePath.substring(projectName.length() + 1);
                VirtualFile referencedFile;
                Project referencedProject;

                com.intellij.openapi.application.AccessToken accessToken = ReadAction.start();

                try {
                    if (relativeResourcePath.startsWith("classpath:/")) {
                        String typeName = relativeResourcePath.substring("classpath:/".length());
                        referencedFile = JarFileSystem.getInstance().findFileByPath(typeName);
                        referencedProject = Utils.findReferencedProject(projectName);
                    } else {
                        referencedFile = Utils.findReferencedFile(relativeResourcePath, projectName);
                        referencedProject = Utils.findReferencedProject(referencedFile);
                    }

                    if (referencedFile == null | referencedProject == null) return null;
                    Document document = FileDocumentManager.getInstance().getDocument(referencedFile);
                    if (document == null) return null;

                    PsiElement resolve = Utils.getTargetElement(offset, referencedProject, document, false);
                    if (resolve == null) return null;

                    JSONObject result = new JSONObject();
                    result.put("project", projectName);
                    PsiFile containingFile = resolve.getContainingFile();

                    VirtualFile virtualFile = containingFile.getVirtualFile();
                    if (virtualFile == null) virtualFile = containingFile.getOriginalFile().getVirtualFile();
                    // todo jar
                    if (virtualFile.isInLocalFileSystem()) {
                        result.put("resource", VfsUtil.getRelativePath(virtualFile, referencedProject.getBaseDir()));
                    } else {
                        result.put("resource", "classpath:/" + virtualFile.getPath());
                    }

                    TextRange textRange = null;
                    if (resolve instanceof PsiNameIdentifierOwner) {
                        PsiElement nameIdentifier = ((PsiNameIdentifierOwner) resolve).getNameIdentifier();
                        if (nameIdentifier != null) textRange = nameIdentifier.getTextRange();
                    }
                    if (textRange == null) textRange = resolve.getTextRange();
                    result.put("offset", textRange.getStartOffset());
                    result.put("length", textRange.getLength());

                    return result;
                } finally {
                    accessToken.finish();
                }

            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

}
