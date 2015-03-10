package org.intellij.flux;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.eclipse.flux.core.AbstractMessageHandler;
import org.eclipse.flux.core.IMessageHandler;
import org.eclipse.flux.core.IMessagingConnector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by maximmossienko on 20/02/15.
 */
public class RenameService {
    private final IMessagingConnector messagingConnector;
    private final Repository repository;

    public RenameService(IMessagingConnector messagingConnector, Repository repository) {
        this.messagingConnector = messagingConnector;
        this.repository = repository;

        IMessageHandler contentAssistRequestHandler = new AbstractMessageHandler("renameinfilerequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                handleRenameInFileRequest(message);
            }
        };
        messagingConnector.addMessageHandler(contentAssistRequestHandler);
    }

    protected void handleRenameInFileRequest(JSONObject message) {
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

                JSONArray references = computeReferences(username, liveEditID, offset, length);

                if (references != null) {
                    JSONObject responseMessage = new JSONObject();
                    responseMessage.put("username", username);
                    responseMessage.put("project", projectName);
                    responseMessage.put("resource", resourcePath);
                    responseMessage.put("callback_id", callbackID);
                    responseMessage.put("requestSenderID", sender);
                    responseMessage.put("references", references);

                    messagingConnector.send("renameinfileresponse", responseMessage);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public JSONArray computeReferences(String username, String resourcePath, int offset, int length) {
        try {
            String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
            String relativeResourcePath = resourcePath.substring(projectName.length() + 1);
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

                PsiElement resolve = Utils.getTargetElement(offset, referencedProject, document, true);
                // only local symbols now
                if (resolve == null || !referencedFile.equals(resolve.getContainingFile().getVirtualFile())) return null;

                JSONArray references = new JSONArray();
                int nameRefOffset = -1;

                if (resolve instanceof PsiNameIdentifierOwner) {
                    PsiElement nameIdentifier = ((PsiNameIdentifierOwner) resolve).getNameIdentifier();
                    if (nameIdentifier != null) {
                        JSONObject nodeObject = new JSONObject();
                        TextRange textRange = nameIdentifier.getTextRange();
                        nodeObject.put("offset", nameRefOffset = textRange.getStartOffset());
                        nodeObject.put("length", textRange.getLength());
                        references.put(nodeObject);
                    }
                }

                for(PsiReference ref:ReferencesSearch.search(resolve, new LocalSearchScope(resolve.getContainingFile())).findAll()) {
                    JSONObject nodeObject = new JSONObject();
                    TextRange elementRange = ref.getElement().getTextRange();
                    TextRange rangeInElement = ref.getRangeInElement();
                    int refOffset = elementRange.getStartOffset() + rangeInElement.getStartOffset();
                    if (refOffset == nameRefOffset) continue;
                    nodeObject.put("offset", refOffset);
                    nodeObject.put("length", rangeInElement.getLength());

                    references.put(nodeObject);
                }
                return references;
            } finally {
                accessToken.finish();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

}
