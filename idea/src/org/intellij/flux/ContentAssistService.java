package org.intellij.flux;

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.eclipse.flux.core.AbstractMessageHandler;
import org.eclipse.flux.core.IMessageHandler;
import org.eclipse.flux.core.IMessagingConnector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Created by maximmossienko on 20/02/15.
 */
public class ContentAssistService {
    private final IMessagingConnector messagingConnector;
    private final Repository repository;

    public ContentAssistService(IMessagingConnector messagingConnector, Repository repository) {
        this.messagingConnector = messagingConnector;
        this.repository = repository;

        IMessageHandler contentAssistRequestHandler = new AbstractMessageHandler("contentassistrequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                handleContentAssistRequest(message);
            }
        };
        messagingConnector.addMessageHandler(contentAssistRequestHandler);
    }

    protected void handleContentAssistRequest(JSONObject message) {
        try {
            String username = message.getString("username");
            String projectName = message.getString("project");
            String resourcePath = message.getString("resource");
            int callbackID = message.getInt("callback_id");

            String liveEditID = projectName + "/" + resourcePath;
            if (repository.getUsername().equals(username) /*liveEditUnits.isLiveEditResource(username, liveEditID)*/) {

                int offset = message.getInt("offset");
                String prefix = message.optString("prefix");
                String sender = message.getString("requestSenderID");

                String proposalsSource = computeContentAssist(username, liveEditID, offset, prefix);

                JSONObject responseMessage = new JSONObject();
                responseMessage.put("username", username);
                responseMessage.put("project", projectName);
                responseMessage.put("resource", resourcePath);
                responseMessage.put("callback_id", callbackID);
                responseMessage.put("requestSenderID", sender);

                JSONArray proposals = new JSONArray(proposalsSource);
                responseMessage.put("proposals", proposals);

                messagingConnector.send("contentassistresponse", responseMessage);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    protected String computeContentAssist(String username, String resourcePath, int offset, String prefix) {
        if (repository.getUsername().equals(username) /*&& resourcePath.endsWith(".java")*/) {

            String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
            String relativeResourcePath = resourcePath.substring(projectName.length() + 1);

            VirtualFile referencedFile = Utils.findReferencedFile(relativeResourcePath, projectName);
            Project referencedProject = Utils.findReferencedProject(referencedFile);

            if (referencedFile == null || referencedProject == null) return "[]";

            Ref<LookupElement[]> elementsRef = new Ref<>();
            ApplicationManager.getApplication().invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    Editor editor = FileEditorManager.getInstance(referencedProject).openTextEditor(new OpenFileDescriptor(referencedProject, referencedFile, offset), false);
                    if (editor == null) {
                        elementsRef.set(LookupElement.EMPTY_ARRAY);
                        return;
                    }

                    final Ref<LookupImpl> lookupRef = new Ref<LookupImpl>();
                    PropertyChangeListener propertyChangeListener = new PropertyChangeListener() {
                        @Override
                        public void propertyChange(PropertyChangeEvent evt) {
                            if ("activeLookup".equals(evt.getPropertyName()) && lookupRef.get() == null) {
                                lookupRef.set((LookupImpl) evt.getNewValue());
                            }
                        }
                    };
                    LookupManager.getInstance(referencedProject).addPropertyChangeListener(propertyChangeListener);
                    new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(referencedProject, editor);
                    LookupManager.getInstance(referencedProject).removePropertyChangeListener(propertyChangeListener);
                    LookupImpl lookup = lookupRef.get();
                    elementsRef.set(lookup == null ? LookupElement.EMPTY_ARRAY : lookup.getItems().toArray(new LookupElement[lookup.getItems().size()]));
                }
            }, ModalityState.any());

            com.intellij.openapi.application.AccessToken accessToken = ReadAction.start();
            try {
                LookupElement[] items = elementsRef.get();

                StringBuilder result = new StringBuilder();
                boolean flag = false;
                result.append("[");

                for (LookupElement proposal : items) {
                    String completion = getCompletion(proposal, prefix);
                    String description = getDescription(proposal);

                    String positions = getPositions(proposal, prefix, offset);

                    if (flag) {
                        result.append(",");
                    }

                    result.append("{");
                    result.append("\"proposal\"");
                    result.append(":");
                    result.append("\"");
                    result.append(completion);
                    result.append("\"");
                    if (description != null) {
                        result.append(",\"description\"");
                        result.append(":");
                        result.append(description);
                        result.append(",\"style\":\"attributedString\"");
                    }

                    if (positions != null) {
                        result.append(",\"positions\"");
                        result.append(":");
                        result.append(positions);
                    }

                    result.append(",\"replace\"");
                    result.append(":");
                    result.append("true");
                    result.append("}");

                    flag = true;
                }
                result.append("]");
                return result.toString();
            } finally {
                accessToken.finish();
            }
        }
        return "[]";
    }

    private String getPositions(LookupElement proposal, String prefix, int globalOffset) {
        if (proposal.getPsiElement() instanceof PsiMethod) {
            String completion = proposal.getLookupString();
            if (completion.startsWith(prefix)) {
                completion = completion.substring(prefix.length());
            }

            StringBuilder positions = new StringBuilder();
            positions.append("[");

            completion += "()";
            PsiParameter[] parameters = ((PsiMethod) proposal.getPsiElement()).getParameterList().getParameters();
            if (parameters.length > 0) {
                int offset = globalOffset;
                offset += completion.length() - 1;

                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) {
                        positions.append(",");
                    }
                    positions.append("{");
                    positions.append("\"offset\"");
                    positions.append(":");
                    positions.append(offset);

                    positions.append(",");
                    positions.append("\"length\"");
                    positions.append(":");
                    positions.append(parameters[i].getName().length());

                    positions.append("}");

                    offset += parameters[i].getName().length();
                    offset += ", ".length();
                }
            }

            positions.append("]");
            return positions.toString();
        }
        else {
            return null;
        }
    }

    private String getCompletion(LookupElement lookupElement, String prefix) {
        String completion = lookupElement.getLookupString();

        if (completion.startsWith(prefix)) {
            completion = completion.substring(prefix.length());
        }

        if (lookupElement.getPsiElement() instanceof PsiMethod) {
            completion += "(";
            PsiParameter[] parameters = ((PsiMethod) lookupElement.getPsiElement()).getParameterList().getParameters();
            if (parameters.length > 0) {
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) {
                        completion += ", ";
                    }
                    completion += parameters[i].getName();
                }
            }
            completion += ")";
        }

        return completion;
    }

    // method name completion starts live template ?
    private String getDescription(LookupElement proposal) {
        PsiElement psiElement = proposal.getPsiElement();

        StringBuilder description = new StringBuilder();
        description.append("{");

        if( psiElement instanceof PsiMethod ) {
            description.append("\"icon\":{\"src\":\"../js/editor/textview/methpub_obj.gif\"},");
            description.append("\"segments\": ");
            description.append("[");

            PsiParameter[] parameters = ((PsiMethod) psiElement).getParameterList().getParameters();

            String sig = ((PsiMethod) psiElement).getName();
            sig += "(";
            for (int i = 0; i < parameters.length; i++) {
                if (i != 0) sig += ", ";
                sig += parameters[i].getType().getPresentableText();
                sig += " ";
                sig += parameters[i].getName();
            }

            sig += ")";


            description.append("{");
            PsiType returnType = ((PsiMethod) psiElement).getReturnType();
            String result = sig + " : " + (returnType != null ? returnType.getPresentableText() : "unknown");
            description.append("\"value\":\"" +result +"\"");
            description.append("}");

            description.append(",");
            description.append("{");
            String appendix = " - " + ((PsiMethod) psiElement).getContainingClass().getName();
            description.append("\"value\":\"" +appendix +"\",");
            description.append("\"style\":{");
            description.append("\"color\":\"#AAAAAA\"");
            description.append("}");
            description.append("}");

            description.append("]");

        } else if( psiElement instanceof PsiField) {
            description.append("\"icon\":{\"src\":\"../js/editor/textview/field_public_obj.gif\"},");
            description.append("\"segments\": ");
            description.append("[");

            description.append("{");
            String result = ((PsiField) psiElement).getName() + " : " + ((PsiField) psiElement).getType().getPresentableText();
            description.append("\"value\":\"" +result +"\"");
            description.append("}");

            description.append(",");
            description.append("{");
            String appendix = " - " +  ((PsiField) psiElement).getContainingClass().getName();
            description.append("\"value\":\"" +appendix +"\",");
            description.append("\"style\":{");
            description.append("\"color\":\"#AAAAAA\"");
            description.append("}");
            description.append("}");

            description.append("]");

        } else if( psiElement instanceof PsiClass ) {

            description.append("\"icon\":{\"src\":\"../js/editor/textview/class_obj.gif\"},");
            description.append("\"segments\": ");
            description.append("[");

            description.append("{");
            String result = ((PsiClass) psiElement).getName();
            description.append("\"value\":\"" +result +"\"");
            description.append("}");

            description.append(",");
            description.append("{");
            String qualifiedName = ((PsiClass) psiElement).getQualifiedName();
            String appendix = " - " + (qualifiedName != null ? StringUtil.getPackageName(qualifiedName) : "unknown");
            description.append("\"value\":\"" +appendix +"\",");
            description.append("\"style\":{");
            description.append("\"color\":\"#AAAAAA\"");
            description.append("}");
            description.append("}");

            description.append("]");

        } else {
            return null;
        }

        description.append("}");
        return description.toString();
    }
}
