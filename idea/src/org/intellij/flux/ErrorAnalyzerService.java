package org.intellij.flux;

import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.GeneralHighlightingPass;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.eclipse.flux.core.IMessagingConnector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Created by maximmossienko on 20/02/15.
 */
public class ErrorAnalyzerService {
    private final IMessagingConnector messagingConnector;
    private final Repository repository;

    public ErrorAnalyzerService(IMessagingConnector messagingConnector, Repository repository) {
        this.messagingConnector = messagingConnector;
        this.repository = repository;
    }

    public void sendProblems(String username, int callbackID, String sender, String projectName, String resourcePath, String messageType) throws JSONException {
        com.intellij.openapi.application.AccessToken accessToken = ReadAction.start();
        try {
            //ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (repository.getUsername().equals(username) /*&& connectedProject != null*/) {
//                Module project = connectedProject.getProject();
//                IResource resource = project.findMember(resourcePath);
                VirtualFile file = Utils.findReferencedFile(resourcePath, projectName);
                Project referencedProject = Utils.findReferencedProject(projectName);
                Document document = file != null ? FileDocumentManager.getInstance().getDocument(file) : null;
                if (file == null || referencedProject == null || document == null) return;
                PsiFile psiFile = PsiDocumentManager.getInstance(referencedProject).getPsiFile(document);
                if (psiFile == null) return;

                JSONObject message = new JSONObject();
                message.put("callback_id", callbackID);
                message.put("requestSenderID", sender);
                message.put("username", repository.getUsername());
                message.put("project", projectName);
                message.put("resource", resourcePath);
                message.put("type", "marker");

                GeneralHighlightingPass pass = new GeneralHighlightingPass(referencedProject, psiFile, document, 0, document.getTextLength(),
                        false, new ProperTextRange(0, document.getTextLength()), null, HighlightInfoProcessor.getEmpty());
                pass.collectInformation(new DaemonProgressIndicator());

                String markerJSON = toJSON(pass.getInfos(), document);
                JSONArray content = new JSONArray(markerJSON);
                message.put("problems", content);

                messagingConnector.send(messageType, message);
            }
        } finally {
            accessToken.finish();
        }
    }

    private String toJSON(List<HighlightInfo> markers, Document document) {
        StringBuilder result = new StringBuilder();
        boolean flag = false;
        result.append("[");
        for (HighlightInfo m : markers) {
            if (m.getDescription() == null) continue;
            if (flag) {
                result.append(",");
            }

            result.append("{");
            result.append("\"description\":" + JSONObject.quote(m.getDescription()));
            result.append(",\"line\":" + document.getLineNumber(m.getStartOffset()));
            result.append(",\"severity\":\"" + (m.getSeverity() == HighlightSeverity.ERROR ? "error" : "warning")
                    + "\"");
            result.append(",\"start\":" + m.getStartOffset());
            result.append(",\"end\":" + (m.getEndOffset() + 1));
            result.append("}");

            flag = true;
        }
        result.append("]");
        return result.toString();
    }

}
