/*******************************************************************************
 * @license
 *  Copyright (c) 2013 GoPivotal, Inc.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      Martin Lippert (GoPivotal, Inc.) - initial API and implementation
 *******************************************************************************/

var MessagesRepository = function(repository) {
	that = this;
	this.repository = repository;
};

exports.MessagesRepository = MessagesRepository;

MessagesRepository.prototype.setSocket = function(socket) {
	that.socket = socket;
	
	socket.on('getProjectsRequest', that.getProjects);
	socket.on('getProjectRequest', that.getProject);
	socket.on('getResourceRequest', that.getResource);
	
	socket.on('getProjectResponse', that.getProjectResponse);
	socket.on('getResourceResponse', that.getResourceResponse);
	
	socket.on('projectConnected', that.projectConnected);
	socket.on('projectDisconnected', that.projectDisconnected);
	
	socket.on('resourceChanged', that.resourceChanged);
	socket.on('resourceCreated', that.resourceCreated);
	socket.on('resourceDeleted', that.resourceDeleted);
	
}

MessagesRepository.prototype.getProjects = function(data) {
    that.repository.getProjects(function(error, result) {
		if (error == null) {
			that.socket.emit('getProjectsResponse', {
				'callback_id' : data.callback_id,
				'requestSenderID' : data.requestSenderID,
				'projects' : result});
		}
    });
}

MessagesRepository.prototype.getProject = function(data) {
    that.repository.getProject(data.project, function(error, resources) {
		if (error == null) {
			that.socket.emit('getProjectResponse', {
				'callback_id' : data.callback_id,
				'requestSenderID' : data.requestSenderID,
				'project' : data.project,
				'files' : resources});
		}
    });
}

MessagesRepository.prototype.getResource = function(data) {
	that.repository.getResource(data.project, data.resource, data.timestamp, data.hash, function(error, content, timestamp, hash) {
		if (error == null) {
			that.socket.emit('getResourceResponse', {
				'callback_id' : data.callback_id,
				'requestSenderID' : data.requestSenderID,
				'project' : data.project,
				'resource' : data.resource,
				'timestamp' : timestamp,
				'hash' : hash,
				'content' : content});
		}
	});
}

MessagesRepository.prototype.projectConnected = function(data) {
	var projectName = data.project;
	if (!that.repository.hasProject(projectName)) {
		that.repository.createProject(projectName, function(error, result) {});
	}
	
	that.socket.emit('getProjectRequest', {
		'callback_id' : 0,
		'project' : projectName
	});
}

MessagesRepository.prototype.projectDisconnected = function(data) {	
}

MessagesRepository.prototype.getProjectResponse = function(data) {
	var projectName = data.project;
	var files = data.files;
	
	if (that.repository.hasProject(projectName)) {
		for (i = 0; i < files.length; i += 1) {
			var resource = files[i].path;
			var type = files[i].type;
			var timestamp = files[i].timestamp;
			var hash = files[i].hash;
			
			if (!that.repository.hasResource(projectName, resource, type) || that.repository.needsUpdate(projectName, resource, type, timestamp, hash)) {
				that.socket.emit('getResourceRequest', {
					'callback_id' : 0,
					'project' : projectName,
					'resource' : resource,
					'timestamp' : timestamp,
					'hash' : hash
				});
			}
		}
	}
}

MessagesRepository.prototype.getResourceResponse = function(data) {
	var projectName = data.project;
	var resource = data.resource;
	var type = data.type;
	var timestamp = data.timestamp;
	var hash = data.hash;
	var content = data.content;
	
	if (!that.repository.hasResource(projectName, resource, type)) {
		that.repository.createResource(projectName, resource, content, hash, timestamp, type, function(error, result) {
			if (error !== null) {
				console.log('Error creating repository resource: ' + projectName + "/" + resource + " - " + data.timestamp);
			}
		});
	}
	else {
		that.repository.updateResource(projectName, resource, content, hash, timestamp, function(error, result) {
			if (error !== null) {
				console.log('Error updating repository resource: ' + data.project + "/" + data.resource + " - " + data.timestamp);
			}
		});
	}
}

MessagesRepository.prototype.resourceChanged = function(data) {
	var projectName = data.project;
	var resource = data.resource;
	var timestamp = data.timestamp;
	var hash = data.hash;
	var type = "file";
	
	if (!that.repository.hasResource(projectName, resource, type) || that.repository.needsUpdate(projectName, resource, type, timestamp, hash)) {
		that.socket.emit('getResourceRequest', {
			'callback_id' : 0,
			'project' : projectName,
			'resource' : resource,
			'timestamp' : timestamp,
			'hash' : hash
		});
	}
	
}

MessagesRepository.prototype.resourceCreated = function(data) {
	var projectName = data.project;
	var resource = data.resource;
	var timestamp = data.timestamp;
	var hash = data.hash;
	var type = data.type;
	
	if (!that.repository.hasResource(projectName, resource, type)) {
		that.socket.emit('getResourceRequest', {
			'callback_id' : 0,
			'project' : projectName,
			'resource' : resource,
			'timestamp' : timestamp,
			'hash' : hash
		});
	}
	
}

MessagesRepository.prototype.resourceDeleted = function(data) {
	var projectName = data.project;
	var resource = data.resource;
	var timestamp = data.timestamp;
	
	// TODO	
}
