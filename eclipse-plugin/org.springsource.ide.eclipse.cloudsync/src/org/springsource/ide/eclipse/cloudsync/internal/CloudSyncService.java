/*******************************************************************************
 *  Copyright (c) 2013 GoPivotal, Inc.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      GoPivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.cloudsync.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * @author Martin Lippert
 */
public class CloudSyncService {
	
	private String api;

	public CloudSyncService(String apiURL) {
		this.api = apiURL;
	}

	public ConnectedProject connect(IProject project) {
		ConnectedProject connectedProject = getProject(project);
		if (connectedProject != null) {
			triggerInitialSync(connectedProject);
		} else {
			connectedProject = createProject(project);
			triggerInitialUpload(connectedProject);
		}

		return connectedProject;
	}

	public void disconnect(IProject project) {
	}

	public void sendResourceUpdate(ConnectedProject project, IResourceDelta delta) {
		IResource resource = delta.getResource();

		if (resource != null && resource.isDerived(IResource.CHECK_ANCESTORS)) {
			return;
		}
		
		switch (delta.getKind()) {
		case IResourceDelta.ADDED:
			createResource(project, resource);
			break;
		case IResourceDelta.REMOVED:
			deleteResource(project, resource);
			break;
		case IResourceDelta.CHANGED:
			if (resource != null && resource instanceof IFile) {
				IFile file = (IFile) resource;
				String checksum = checksum(file);
				String updateChecksum = project.getHash(resource.getProjectRelativePath().toString());
				
				if (updateChecksum == null || (checksum != null && !checksum.equals(updateChecksum))) {
					updateResource(project, resource);
				}
			}
			else {
				updateResource(project, resource);
			}

			break;
		}
	}
	
	public void receivedResourceUpdate(ConnectedProject connectedProject, String resourcePath, int newVersion, String fingerprint) {
		IProject project = connectedProject.getProject();
		IResource resource = project.findMember(resourcePath);

		if (resource != null && resource instanceof IFile) {
			IFile file = (IFile) resource;

			String checksum = checksum(file);
			if (checksum != null && !checksum.equals(fingerprint)) {
				try {
					byte[] newResourceContent = getResource(project, resourcePath);
					connectedProject.setHash(resourcePath, fingerprint);
					file.setContents(new ByteArrayInputStream(newResourceContent), true, true, null);
				} catch (CoreException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public ConnectedProject getProject(IProject project) {
		try {
			URL url = new URL(api + project.getName());
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("GET");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(false);
			urlConn.setRequestProperty("accept", "application/json");

			int rspCode = urlConn.getResponseCode();
			if (rspCode == HttpURLConnection.HTTP_OK) {
				return ConnectedProject.readFromJSON(urlConn.getInputStream(), project);
			}
			else {
				System.err.println("getProject returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public ConnectedProject createProject(IProject project) {
		try {
			URL url = new URL(api + project.getName());
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("POST");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(false);
			urlConn.setRequestProperty("accept", "application/json");
			urlConn.setRequestProperty( "Content-Length", "0");

			int rspCode = urlConn.getResponseCode();

			if (rspCode == HttpURLConnection.HTTP_OK) {
				return ConnectedProject.readFromJSON(urlConn.getInputStream(), project);
			}
			else {
				System.err.println("createProject returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public void createResource(ConnectedProject project, IResource resource) {
		if (project == resource)
			return;
		if (resource.getType() == IResource.PROJECT)
			return;

		try {
			URL url = new URL(api + project.getName() + "/" + resource.getProjectRelativePath());
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("POST");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(true);

			if (resource instanceof IFile) {
				IFileStore store = EFS.getStore(resource.getLocationURI());
				long length = store.fetchInfo().getLength();
				urlConn.setRequestProperty("Content-Length", String.valueOf(length));
				urlConn.setRequestProperty("resource-type", "file");
				
				IFile file = (IFile) resource;

				OutputStream outputStream = urlConn.getOutputStream();
				pipe(file.getContents(), outputStream);
				outputStream.flush();
				outputStream.close();
			}
			else if (resource instanceof IFolder) {
				urlConn.setRequestProperty( "Content-Length", "0");
				urlConn.setRequestProperty("resource-type", "folder");
			}
			
			int rspCode = urlConn.getResponseCode();
			if (rspCode != HttpURLConnection.HTTP_OK) {
				throw new Exception("error " + rspCode + " while putting resource: " + resource.getProjectRelativePath());
			}
			else {
				System.err.println("createResource returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void updateResource(ConnectedProject project, IResource resource) {
		if (project == resource)
			return;
		if (resource.getType() == IResource.FOLDER)
			return;
		if (resource.getType() == IResource.PROJECT)
			return;
		
		try {
			String resourcePath = resource.getProjectRelativePath().toString();

			URL url = new URL(api + project.getName() + "/" + resourcePath);
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("PUT");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(true);

			if (resource instanceof IFile) {
				IFileStore store = EFS.getStore(resource.getLocationURI());
				long length = store.fetchInfo().getLength();
				urlConn.setRequestProperty( "Content-Length", String.valueOf(length));
				urlConn.setRequestProperty("resource-type", "file");
				IFile file = (IFile) resource;

				OutputStream outputStream = urlConn.getOutputStream();
				pipe(file.getContents(), outputStream);
				outputStream.flush();
			}
			else {
				urlConn.setRequestProperty( "Content-Length", "0");
			}

			int rspCode = urlConn.getResponseCode();
			if (rspCode != HttpURLConnection.HTTP_OK) {
				throw new Exception("error " + rspCode + " while updating resource: " + resource.getProjectRelativePath());
			}
			else {
				System.err.println("updateResource returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}

			String result = IOUtils.toString(urlConn.getInputStream());
			JSONTokener tokener = new JSONTokener(result);
			JSONObject returnJSONObject = new JSONObject(tokener);

			String fingerprint = returnJSONObject.getString("fingerprint");
			project.setHash(resourcePath, fingerprint);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void updateMetadata(ConnectedProject project, IResource resource) {
		try {
			IMarker[] markers = resource.findMarkers(null, true, IResource.DEPTH_INFINITE);
			String markerJSON = toJSON(markers);
			
			URL url = new URL(api + project.getName() + "/" + resource.getProjectRelativePath().toString() + "?meta=marker");
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("PUT");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(true);

			urlConn.setRequestProperty( "Content-Length", String.valueOf(markerJSON.length()));

			PrintWriter output = new PrintWriter(urlConn.getOutputStream());
			output.write(markerJSON);
			output.flush();

			int rspCode = urlConn.getResponseCode();
			if (rspCode != HttpURLConnection.HTTP_OK) {
				throw new Exception("error " + rspCode + " while updating resource metadata: " + resource.getProjectRelativePath());
			}
			else {
				System.err.println("updateMetadata returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public byte[] getResource(IProject project, String resourcePath) {
		try {
			URL url = new URL(api + project.getName() + "/" + resourcePath);
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("GET");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(false);
			urlConn.setDoInput(true);

			int rspCode = urlConn.getResponseCode();

			if (rspCode == HttpURLConnection.HTTP_OK) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				pipe(urlConn.getInputStream(), bos);
				return bos.toByteArray();
			}
			else {
				System.err.println("getResource returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public void deleteResource(ConnectedProject project, IResource resource) {
		if (project == resource)
			return;

		try {
			URL url = new URL(api + project.getName() + "/" + resource.getProjectRelativePath());
			HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
			urlConn.setRequestMethod("DELETE");
			urlConn.setAllowUserInteraction(false); // no user interaction
			urlConn.setDoOutput(false);
			urlConn.setDoInput(true);

			int rspCode = urlConn.getResponseCode();

			if (rspCode != HttpURLConnection.HTTP_OK) {
				throw new Exception("error " + rspCode + " while deleting resource: " + resource.getProjectRelativePath());
			}
			else {
				System.err.println("deleteResource returned: " + rspCode);
				System.err.println(urlConn.getResponseMessage());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void pipe(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[1024];
		int bytesRead;
		while ((bytesRead = input.read(buffer)) != -1) {
			output.write(buffer, 0, bytesRead);
		}

		input.close();
	}

	private void triggerInitialUpload(final ConnectedProject connectedProject) {
		IProject project = connectedProject.getProject();

		try {
			project.accept(new IResourceVisitor() {
				@Override
				public boolean visit(IResource resource) throws CoreException {
					System.out.println("upload resource: " + resource.getName());
					IProject project = resource.getProject();
					if (project != null) {
						createResource(connectedProject, resource);
					}
					return true;
				}
			}, IResource.DEPTH_INFINITE, IContainer.EXCLUDE_DERIVED);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void triggerInitialSync(ConnectedProject connectedProject) {
		// triggerInitialUpload(connectedProject);
	}

	public String checksum(IFile file) {
		try {
			return DigestUtils.shaHex(file.getContents(true));
		} catch (Exception e) {
			return null;
		}
	}
	
	public String toJSON(IMarker[] markers) {
		StringBuilder result = new StringBuilder();
		boolean flag = false;
		result.append("[");
		for (IMarker m : markers) {
			if (flag) {
				result.append(",");
			}

			result.append("{");
			result.append("\"description\":" + JSONObject.quote(m.getAttribute("message", "")));
			result.append(",\"line\":" + m.getAttribute("lineNumber", 0));
			result.append(",\"severity\":\"" + (m.getAttribute("severity", IMarker.SEVERITY_WARNING) == IMarker.SEVERITY_ERROR ? "error" : "warning") + "\"");
			result.append(",\"start\":" + m.getAttribute("charStart", 0));
			result.append(",\"end\":" + m.getAttribute("charEnd", 0));
			result.append("}");

			flag = true;
		}
		result.append("]");
		return result.toString();
	}

}