package org.intellij.flux;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.flux.core.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Created by maximmossienko on 17/02/15.
 */
public class Repository {
    private final String username;
    private final IMessagingConnector messagingConnector;

    private static int GET_PROJECT_CALLBACK = "Repository - getProjectCallback".hashCode();
    private static int GET_RESOURCE_CALLBACK = "Repository - getResourceCallback".hashCode();

    public Repository(IMessagingConnector messagingConnector,String username) {
        this.username = username;
        this.messagingConnector = messagingConnector;

        IMessageHandler resourceChangedHandler = new AbstractMessageHandler("resourceChanged") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                updateResource(message);
            }
        };
        this.messagingConnector.addMessageHandler(resourceChangedHandler);

        IMessageHandler resourceCreatedHandler = new AbstractMessageHandler("resourceCreated") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                createResource(message);
            }
        };
        this.messagingConnector.addMessageHandler(resourceCreatedHandler);

        IMessageHandler resourceDeletedHandler = new AbstractMessageHandler("resourceDeleted") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                deleteResource(message);
            }
        };
        this.messagingConnector.addMessageHandler(resourceDeletedHandler);

        IMessageHandler getProjectsRequestHandler = new AbstractMessageHandler("getProjectsRequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                getProjects(message);
            }
        };
        this.messagingConnector.addMessageHandler(getProjectsRequestHandler);

        IMessageHandler getProjectRequestHandler = new AbstractMessageHandler("getProjectRequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                getProject(message);
            }
        };
        this.messagingConnector.addMessageHandler(getProjectRequestHandler);

        IMessageHandler getProjectResponseHandler = new CallbackIDAwareMessageHandler("getProjectResponse", Repository.GET_PROJECT_CALLBACK) {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                getProjectResponse(message);
            }
        };
        this.messagingConnector.addMessageHandler(getProjectResponseHandler);

        IMessageHandler getResourceRequestHandler = new AbstractMessageHandler("getResourceRequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                try {
                    final String resourcePath = message.getString("resource");

                    if (resourcePath.startsWith("classpath:")) {
                        getClasspathResource(message);
                    }
                    else {
                        getResource(message);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        this.messagingConnector.addMessageHandler(getResourceRequestHandler);

        IMessageHandler getResourceResponseHandler = new CallbackIDAwareMessageHandler("getResourceResponse", Repository.GET_RESOURCE_CALLBACK) {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                getResourceResponse(message);
            }
        };
        this.messagingConnector.addMessageHandler(getResourceResponseHandler);

        IMessageHandler getMetadataRequestHandler = new AbstractMessageHandler("getMetadataRequest") {
            @Override
            public void handleMessage(String messageType, JSONObject message) {
                getMetadata(message);
            }
        };
        this.messagingConnector.addMessageHandler(getMetadataRequestHandler);

        ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerListener() {
            @Override
            public void projectOpened(Project project) {
                if (project.isDefault()) return;
                sendProjectConnectedMessage(project.getName());
                notifyProjectConnected(project);

                if (/*isConnected()*/ true) {
                    //sendProjectConnectedMessage(project.getName());
                    syncConnectedProject(project.getName());
                }
            }

            @Override
            public boolean canCloseProject(Project project) {
                return true;
            }

            @Override
            public void projectClosed(Project project) {

            }

            @Override
            public void projectClosing(Project project) {
                notifyProjectDisconnected(project);

                if (true /*isConnected()*/) {
                    try {
                        JSONObject message = new JSONObject();
                        message.put("username", Repository.this.username);
                        message.put("project", project.getName());
                        messagingConnector.send("projectDisconnected", message);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

    }

    public String getUsername() {
        return username;
    }

    public void updateResource(JSONObject request) {
        try {
            final String username = request.getString("username");
            final String projectName = request.getString("project");
            final String resourcePath = request.getString("resource");
            final long updateTimestamp = request.getLong("timestamp");
            final String updateHash = request.optString("hash");

//            ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null*/) {
                VirtualFile resource = Utils.findReferencedFile(resourcePath, projectName);
                boolean stored = false;

                if (resource != null) {
                    if (!resource.isDirectory()) {
//                        String localHash = connectedProject.getHash(resourcePath);
//                        long localTimestamp = connectedProject.getTimestamp(resourcePath);
                        Document cachedDocument = FileDocumentManager.getInstance().getCachedDocument(resource);
                        String localHash = cachedDocument != null ? DigestUtils.shaHex(cachedDocument.getText()):
                                DigestUtils.shaHex(resource.getInputStream());
                        long localTimestamp = cachedDocument != null ? cachedDocument.getModificationStamp()  : resource.getModificationStamp();

                        if (!Comparing.equal(localHash, updateHash) && localTimestamp < updateTimestamp) {
                            String newResourceContent = request.getString("content");

                            if (cachedDocument != null) cachedDocument.setText(newResourceContent);
                            else VfsUtil.saveText(resource, newResourceContent);
                            stored = true;
                        }
                    }
                }
                else {
                    String newResourceContent = request.getString("content");
                    int i = resourcePath.lastIndexOf('/');
                    VirtualFile resourceDir = Utils.findReferencedFile(resourcePath.substring(0, i), projectName);
                    String newFileName = resourcePath.substring(i + 1);
                    if (resourceDir != null) {
                        VirtualFile childData = resourceDir.createChildData(this, newFileName);
                        VfsUtil.saveText(childData, newResourceContent);
                    }
                }

                if (stored) {
                    JSONObject message = new JSONObject();
                    message.put("username", this.username);
                    message.put("project", projectName);
                    message.put("resource", resourcePath);
                    message.put("timestamp", updateTimestamp);
                    message.put("hash", updateHash);
                    messagingConnector.send("resourceStored", message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void createResource(JSONObject request) {
        int a = 1;
        /*try {
            final String username = request.getString("username");
            final String projectName = request.getString("project");
            final String resourcePath = request.getString("resource");
            final long updateTimestamp = request.getLong("timestamp");
            final String updateHash = request.optString("hash");
            final String type = request.optString("type");

            ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) && connectedProject != null) {
                Module project = connectedProject.getProject();
                IResource resource = project.findMember(resourcePath);

                if (resource == null) {
                    if ("folder".equals(type)) {
                        IFolder newFolder = project.getFolder(resourcePath);

                        connectedProject.setHash(resourcePath, updateHash);
                        connectedProject.setTimestamp(resourcePath, updateTimestamp);

                        newFolder.create(true, true, null);
                        newFolder.setLocalTimeStamp(updateTimestamp);
                    }
                    else if ("file".equals(type)) {
                        JSONObject message = new JSONObject();
                        message.put("callback_id", GET_RESOURCE_CALLBACK);
                        message.put("username", this.username);
                        message.put("project", projectName);
                        message.put("resource", resourcePath);
                        message.put("timestamp", updateTimestamp);
                        message.put("hash", updateHash);

                        messagingConnector.send("getResourceRequest", message);
                    }
                }
                else {
                    // TODO
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }*/
    }

    public void deleteResource(JSONObject request) {
        int a = 1;
        /*try {
            final String username = request.getString("username");
            final String projectName = request.getString("project");
            final String resourcePath = request.getString("resource");
            final long deletedTimestamp = request.getLong("timestamp");

            ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) && connectedProject != null) {
                Module project = connectedProject.getProject();
                IResource resource = project.findMember(resourcePath);

                if (resource != null && resource.exists() && (resource instanceof IFile || resource instanceof IFolder)) {
                    long localTimestamp = connectedProject.getTimestamp(resourcePath);

                    if (localTimestamp < deletedTimestamp) {
                        resource.delete(true, null);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }*/
    }

    public void getProjects(JSONObject request) {
        int a = 1;
        /*try {
            int callbackID = request.getInt("callback_id");
            String sender = request.getString("requestSenderID");
            String username = request.getString("username");

            if (this.username.equals(username)) {
                JSONArray projects = new JSONArray();
                for (String projectName : this.syncedProjects.keySet()) {
                    JSONObject proj = new JSONObject();
                    proj.put("name", projectName);
                    projects.put(proj);
                }

                JSONObject message = new JSONObject();
                message.put("callback_id", callbackID);
                message.put("requestSenderID", sender);
                message.put("username", this.username);
                message.put("projects", projects);

                messagingConnector.send("getProjectsResponse", message);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }*/
    }

    public void getProject(JSONObject request) {
        try {
            final int callbackID = request.getInt("callback_id");
            final String sender = request.getString("requestSenderID");
            final String projectName = request.getString("project");
            final String username = request.getString("username");

            //final ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null*/) {

                final JSONArray files = new JSONArray();

                Project project = Utils.findReferencedProject(projectName);
                if (project == null) return;

                try {
                    VirtualFile baseDir = project.getBaseDir();
                    ProjectRootManager.getInstance(project).getFileIndex().iterateContent(new ContentIterator() {
                        @Override
                        public boolean processFile(VirtualFile virtualFile) {
                            JSONObject projectResource = new JSONObject();
                            String path = VfsUtil.getRelativePath(virtualFile, baseDir);
                            try {
                                projectResource.put("path", path);
                                Document cachedDocument = virtualFile.isDirectory() ? null : FileDocumentManager.getInstance().getCachedDocument(virtualFile);
                                projectResource.put("timestamp", cachedDocument != null ? cachedDocument.getModificationStamp() : virtualFile.getModificationStamp());

                                projectResource.put("hash", cachedDocument != null ? DigestUtils.shaHex(cachedDocument.getText()) : virtualFile.isDirectory() ? "0" : DigestUtils.shaHex(virtualFile.getInputStream()));

                                if (!virtualFile.isDirectory()) {
                                    projectResource.put("type", "file");
                                } else {
                                    projectResource.put("type", "folder");
                                }

                                files.put(projectResource);
                            } catch (JSONException | IOException e) {
                                e.printStackTrace();
                            }
                            return true;
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }

                JSONObject message = new JSONObject();
                message.put("callback_id", callbackID);
                message.put("requestSenderID", sender);
                message.put("username", this.username);
                message.put("project", projectName);
                message.put("username", this.username);
                message.put("files", files);

                messagingConnector.send("getProjectResponse", message);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void getProjectResponse(JSONObject response) {
        try {
            final String username = response.getString("username");
            final String projectName = response.getString("project");
            final JSONArray files = response.getJSONArray("files");
            final JSONArray deleted = response.optJSONArray("deleted");

            //ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null*/) {

                for (int i = 0; i < files.length(); i++) {
                    JSONObject resource = files.getJSONObject(i);

                    String resourcePath = resource.getString("path");
                    long timestamp = resource.getLong("timestamp");

                    String type = resource.optString("type");
                    String hash = resource.optString("hash");

//                    boolean newFile = type != null && type.equals("file") && !connectedProject.containsResource(resourcePath);
//                    boolean updatedFileTimestamp =  type != null && type.equals("file") && connectedProject.containsResource(resourcePath)
//                            && connectedProject.getHash(resourcePath).equals(hash) && connectedProject.getTimestamp(resourcePath) < timestamp;
//                    boolean updatedFile = type != null && type.equals("file") && connectedProject.containsResource(resourcePath)
//                            && !connectedProject.getHash(resourcePath).equals(hash) && connectedProject.getTimestamp(resourcePath) < timestamp;

//                    if (newFile || updatedFile) {
//                        JSONObject message = new JSONObject();
//                        message.put("callback_id", GET_RESOURCE_CALLBACK);
//                        message.put("project", projectName);
//                        message.put("username", this.username);
//                        message.put("resource", resourcePath);
//                        message.put("timestamp", timestamp);
//                        message.put("hash", hash);
//
//                        messagingConnector.send("getResourceRequest", message);
//                    }
//
//                    if (updatedFileTimestamp) {
//                        connectedProject.setTimestamp(resourcePath, timestamp);
//                        IResource file  = connectedProject.getProject().findMember(resourcePath);
//                        file.setLocalTimeStamp(timestamp);
//                    }
//
//                    boolean newFolder = type != null && type.equals("folder") && !connectedProject.containsResource(resourcePath);
//                    boolean updatedFolder = type != null && type.equals("folder") && connectedProject.containsResource(resourcePath)
//                            && !connectedProject.getHash(resourcePath).equals(hash) && connectedProject.getTimestamp(resourcePath) < timestamp;
//
//                    if (newFolder) {
//                        Module project = connectedProject.getProject();
//                        IFolder folder = project.getFolder(resourcePath);
//
//                        connectedProject.setHash(resourcePath, hash);
//                        connectedProject.setTimestamp(resourcePath, timestamp);
//
//                        folder.create(true, true, null);
//                        folder.setLocalTimeStamp(timestamp);
//                    }
//                    else if (updatedFolder) {
//                    }
//                }
//
//                if (deleted != null) {
//                    for (int i = 0; i < deleted.length(); i++) {
//                        JSONObject deletedResource = deleted.getJSONObject(i);
//
//                        String resourcePath = deletedResource.getString("path");
//                        long deletedTimestamp = deletedResource.getLong("timestamp");
//
//                        Module project = connectedProject.getProject();
//                        IResource resource = project.findMember(resourcePath);
//
//                        if (resource != null && resource.exists() && (resource instanceof IFile || resource instanceof IFolder)) {
//                            long localTimestamp = connectedProject.getTimestamp(resourcePath);
//
//                            if (localTimestamp < deletedTimestamp) {
//                                resource.delete(true, null);
//                            }
//                        }
//                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void getResource(JSONObject request) {
        try {
            final String username = request.getString("username");
            final int callbackID = request.getInt("callback_id");
            final String sender = request.getString("requestSenderID");
            final String projectName = request.getString("project");
            final String resourcePath = request.getString("resource");

            //ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null && connectedProject.containsResource(resourcePath)*/) {
//                Module project = connectedProject.getProject();
//
//                if (request.has("timestamp") && request.getLong("timestamp") != connectedProject.getTimestamp(resourcePath)) {
//                    return;
//                }
//
//                IResource resource = project.findMember(resourcePath);
                VirtualFile resource = Utils.findReferencedFile(resourcePath, projectName);

                if (resource == null) {
                    return; //?
                }

                com.intellij.openapi.application.AccessToken token = ReadAction.start();
                JSONObject message;
                try {
                    Document document = FileDocumentManager.getInstance().getDocument(resource);

                    message = new JSONObject();
                    message.put("callback_id", callbackID);
                    message.put("requestSenderID", sender);
                    message.put("username", this.username);
                    message.put("project", projectName);
                    message.put("resource", resourcePath);
                    message.put("timestamp", document != null ? document.getModificationStamp() : resource.getModificationStamp());
                    //message.put("hash", connectedProject.getHash(resourcePath));
                    String shaHex = document != null ? DigestUtils.shaHex(document.getText()) : resource.isDirectory() ? "0" :DigestUtils.shaHex(resource.getInputStream());
                    message.put("hash", shaHex); // cache hash

                    if (resource.isDirectory()) {
                        message.put("type", "folder");
                    } else {
                        if (request.has("hash") && !request.getString("hash").equals(shaHex)) {
                            return;
                        }

                        message.put("content", document != null ? document.getText() : "");
                        message.put("type", "file");
                    }
                } finally {
                    token.finish();
                }

                messagingConnector.send("getResourceResponse", message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void getClasspathResource(JSONObject request) {
        try {
            final int callbackID = request.getInt("callback_id");
            final String sender = request.getString("requestSenderID");
            final String projectName = request.getString("project");
            final String resourcePath = request.getString("resource");
            final String username = request.getString("username");

            //ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null*/) {
                String typeName = resourcePath.substring("classpath:/".length());

                VirtualFile fileByPath = JarFileSystem.getInstance().findFileByPath(typeName);
                if (fileByPath != null) {


//                IJavaProject javaProject = JavaCore.create(connectedProject.getProject());
//                if (javaProject != null) {
//                    IType type = javaProject.findType(typeName);
//                    IClassFile classFile = type.getClassFile();
//                    if (classFile != null && classFile.getSourceRange() != null) {

                        JSONObject message = new JSONObject();
                        message.put("callback_id", callbackID);
                        message.put("requestSenderID", sender);
                        message.put("username", this.username);
                        message.put("project", projectName);
                        message.put("resource", resourcePath);
                        message.put("readonly", true);

                        String content = BinaryFileTypeDecompilers.INSTANCE.forFileType(fileByPath.getFileType()).decompile(fileByPath).toString();

                        message.put("content", content);
                        message.put("hash", DigestUtils.shaHex(content) );  //?
                        message.put("type", "file");

                        messagingConnector.send("getResourceResponse", message);
                    }
//                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void getResourceResponse(JSONObject response) {
        try {
            final String username = response.getString("username");
            final String projectName = response.getString("project");
            final String resourcePath = response.getString("resource");
            final long updateTimestamp = response.getLong("timestamp");
            final String updateHash = response.getString("hash");

            //ConnectedProject connectedProject = this.syncedProjects.get(projectName);
            if (this.username.equals(username) /*&& connectedProject != null*/) {
                boolean stored = false;

                //Module project = connectedProject.getProject();
                //IResource resource = project.findMember(resourcePath);
                VirtualFile resource = Utils.findReferencedFile(resourcePath, projectName);

                if (resource != null) {
                    if (!resource.isDirectory()) {
//                        String localHash = connectedProject.getHash(resourcePath);
//                        long localTimestamp = connectedProject.getTimestamp(resourcePath);
                        String localHash = DigestUtils.shaHex(resource.getInputStream());
                        long localTimestamp = resource.getModificationStamp();

                        if (!Comparing.equal(localHash, updateHash) && localTimestamp < updateTimestamp) {
                            String newResourceContent = response.getString("content");

                            VfsUtil.saveText(resource, newResourceContent);
                            stored = true;
                        }
                    }
                }
                else {
                    String newResourceContent = response.getString("content");
                    // todo create new file
//                    VfsUtil.createChildSequent()
//
//                    connectedProject.setHash(resourcePath, updateHash);
//                    connectedProject.setTimestamp(resourcePath, updateTimestamp);
//
//                    newFile.create(new ByteArrayInputStream(newResourceContent.getBytes()), true, null);
//                    newFile.setLocalTimeStamp(updateTimestamp);
//                    stored = true;
                }

                if (stored) {
                    JSONObject message = new JSONObject();
                    message.put("username", this.username);
                    message.put("project", projectName);
                    message.put("resource", resourcePath);
                    message.put("timestamp", updateTimestamp);
                    message.put("hash", updateHash);
                    messagingConnector.send("resourceStored", message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void getMetadata(JSONObject request) {
//        com.intellij.openapi.application.AccessToken accessToken = ReadAction.start();
//        try {
//            final String username = request.getString("username");
//            final int callbackID = request.getInt("callback_id");
//            final String sender = request.getString("requestSenderID");
//            final String projectName = request.getString("project");
//            final String resourcePath = request.getString("resource");
//            //errorAnalyzerService.sendProblems(username, callbackID, sender, projectName, resourcePath, "getMetadataResponse");
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }


    private Collection<IRepositoryListener> repositoryListeners = new ConcurrentLinkedDeque<>();
    public void addRepositoryListener(IRepositoryListener listener) {
        this.repositoryListeners.add(listener);
    }

    public void removeRepositoryListener(IRepositoryListener listener) {
        this.repositoryListeners.remove(listener);
    }

    protected void notifyProjectConnected(Project project) {
        for (IRepositoryListener listener : this.repositoryListeners) {
            listener.projectConnected(project);
        }
    }

    protected void notifyProjectDisconnected(Project project) {
        for (IRepositoryListener listener : this.repositoryListeners) {
            listener.projectDisconnected(project);
        }
    }

    protected void syncConnectedProject(String projectName) {
        try {
            JSONObject message = new JSONObject();
            message.put("username", this.username);
            message.put("project", projectName);
            message.put("includeDeleted", true);
            message.put("callback_id", GET_PROJECT_CALLBACK);
            messagingConnector.send("getProjectRequest", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    protected void sendProjectConnectedMessage(String projectName) {
        try {
            JSONObject message = new JSONObject();
            message.put("username", this.username);
            message.put("project", projectName);
            messagingConnector.send("projectConnected", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
