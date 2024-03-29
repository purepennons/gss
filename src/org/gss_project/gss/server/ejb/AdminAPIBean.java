/*
 * Copyright 2010 Electronic Business Systems Ltd.
 *
 * This file is part of GSS.
 *
 * GSS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GSS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GSS.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gss_project.gss.server.ejb;

import org.gss_project.gss.common.exceptions.InsufficientPermissionsException;
import org.gss_project.gss.common.exceptions.ObjectNotFoundException;
import org.gss_project.gss.server.domain.AccountingInfo;
import org.gss_project.gss.server.domain.AuditInfo;
import org.gss_project.gss.server.domain.FileBody;
import org.gss_project.gss.server.domain.FileHeader;
import org.gss_project.gss.server.domain.FileUploadStatus;
import org.gss_project.gss.server.domain.Folder;
import org.gss_project.gss.server.domain.Group;
import org.gss_project.gss.server.domain.Permission;
import org.gss_project.gss.server.domain.User;
import org.gss_project.gss.server.domain.UserClass;
import org.gss_project.gss.server.domain.UserLogin;
import org.gss_project.gss.common.dto.FileBodyDTO;
import org.gss_project.gss.common.dto.FileHeaderDTO;
import org.gss_project.gss.common.dto.FolderDTO;
import org.gss_project.gss.common.dto.GroupDTO;
import org.gss_project.gss.common.dto.PermissionDTO;
import org.gss_project.gss.common.dto.StatsDTO;
import org.gss_project.gss.common.dto.SystemStatsDTO;
import org.gss_project.gss.common.dto.UserClassDTO;
import org.gss_project.gss.common.dto.UserDTO;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author kman
 */
@Stateless
public class AdminAPIBean implements AdminAPI {
	/**
	 * Injected reference to the ExternalAPI service.
	 */
	@EJB
	private ExternalAPI api;

	/**
	 * The logger.
	 */
	private static Log logger = LogFactory.getLog(AdminAPIBean.class);
	/**
	 * Injected reference to the GSSDAO data access facade.
	 */
	@EJB
	private GSSDAO dao;

	@Override
	public FileHeaderDTO getFile(String uri) throws ObjectNotFoundException {
		if (uri == null)
			throw new ObjectNotFoundException("No uri specified");

		List<String> pathElements = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(uri, "/");
		String username = st.nextToken();
		st.nextToken();
		while (st.hasMoreTokens())
			pathElements.add(st.nextToken());
		if (pathElements.size() < 1)
			throw new ObjectNotFoundException("No file found");
		User owner = dao.getUser(username);
		// Store the last element, since it requires special handling.
		String lastElement = pathElements.remove(pathElements.size() - 1);
		FolderDTO cursor = getRootFolder(owner.getId());
		// Traverse and verify the specified folder path.
		for (String pathElement : pathElements) {
			cursor = getFolder(cursor.getId(), pathElement);
			if (cursor.isDeleted())
				throw new ObjectNotFoundException("Folder " + cursor.getPath() + " not found");
		}
		FileHeaderDTO file = getFile(cursor.getId(), lastElement);
		return file;
	}

	@Override
	public List<FileHeaderDTO> getFiles(String uri) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (uri == null)
			throw new ObjectNotFoundException("No uri specified");
		List<FileHeaderDTO> res = new ArrayList<FileHeaderDTO>();
		List<String> pathElements = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(uri, "/");
		if(st.countTokens()<2)
			return res;
		String username = st.nextToken();
		st.nextToken();
		User owner = dao.getUser(username);
		while (st.hasMoreTokens())
			pathElements.add(st.nextToken());
		if (pathElements.size() < 1){

				FolderDTO folder = getRootFolder(owner.getId());
				res.addAll(getFiles(folder.getOwner().getId(), folder.getId(), false));
				return res;
		}

		// Store the last element, since it requires special handling.
		String lastElement = pathElements.remove(pathElements.size() - 1);
		FolderDTO cursor = getRootFolder(owner.getId());
		// Traverse and verify the specified folder path.
		for (String pathElement : pathElements) {
			cursor = getFolder(cursor.getId(), pathElement);
			if (cursor.isDeleted())
				throw new ObjectNotFoundException("Folder " + cursor.getPath() + " not found");
		}
		try {
			FileHeaderDTO file = getFile(cursor.getId(), lastElement);
			res.add(file);
		} catch (ObjectNotFoundException e) {
			// Perhaps the requested resource is not a file, so
			// check for folders as well.
			FolderDTO folder = getFolder(cursor.getId(), lastElement);
			res.addAll(getFiles(folder.getOwner().getId(), folder.getId(), false));

		}

		return res;
	}

	private FolderDTO getFolder(Long parentId, String name) throws ObjectNotFoundException {
		if (parentId == null)
			throw new ObjectNotFoundException("No parent folder specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("No folder specified");

		Folder folder = dao.getFolder(parentId, name);
		return folder.getDTO();
	}

	private FileHeaderDTO getFile(Long folderId, String name) throws ObjectNotFoundException {
		if (folderId == null)
			throw new ObjectNotFoundException("No parent folder specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("No file specified");

		FileHeader file = dao.getFile(folderId, name);
		FileHeaderDTO dto = file.getDTO();
		Set<Permission> perms = file.getPermissions();
		Set<PermissionDTO> result = new LinkedHashSet<PermissionDTO>();
		for (Permission perm : perms)
			if (perm.getUser() != null && perm.getUser().getId().equals(file.getOwner().getId()))
				result.add(perm.getDTO());
		for (Permission perm : perms)
			if (perm.getUser() != null && perm.getUser().getId().equals(file.getOwner().getId())) {
			} else
				result.add(perm.getDTO());
		dto.setPermissions(result);
		return dto;
	}
	@Override
	public FileHeaderDTO getFile(Long fileId) throws ObjectNotFoundException {
		FileHeader file = dao.getEntityById(FileHeader.class, fileId);
		FileHeaderDTO dto = file.getDTO();
		Set<Permission> perms = file.getPermissions();
		Set<PermissionDTO> result = new LinkedHashSet<PermissionDTO>();
		for (Permission perm : perms)
			if (perm.getUser() != null && perm.getUser().getId().equals(file.getOwner().getId()))
				result.add(perm.getDTO());
		for (Permission perm : perms)
			if (perm.getUser() != null && perm.getUser().getId().equals(file.getOwner().getId())) {
			} else
				result.add(perm.getDTO());
		dto.setPermissions(result);
		return dto;
	}

	@Override
	public UserDTO getUser(Long userId) throws ObjectNotFoundException {
		return api.getUserDTO(userId);
	}

	@Override
	public StatsDTO getUserStatistics(Long userId) throws ObjectNotFoundException {
		StatsDTO stats = api.getUserStatistics(userId);
		User user = dao.getEntityById(User.class, userId);
		AccountingInfo info = dao.getAccountingInfo(user, new Date());
		stats.setBandwithQuotaUsed(info.getBandwidthUsed());
		return stats;
	}

	@Override
	public List<UserDTO> searchUsers(String query) throws ObjectNotFoundException {
		List<User> users = dao.getUsersByUserNameOrEmailLike(query);
		List<UserDTO> result = new ArrayList<UserDTO>();
		for (User u : users){
			UserDTO tempDTO = u.getDTO();
            try {
			    List<UserLogin> userLogins = api.getLastUserLogins(u.getId());
			    tempDTO.setCurrentLoginDate(userLogins.get(0).getLoginDate());
			    tempDTO.setLastLoginDate(userLogins.get(1).getLoginDate());
            }
            catch (ObjectNotFoundException e) {//Do nothing
            }
			result.add(tempDTO);
		}
		return result;
	}

	@Override
	public UserDTO getUser(String username) throws ObjectNotFoundException{
		User u = dao.getUser(username);
		if(u!=null){
			UserDTO tempDTO = u.getDTO();
            try {
			    List<UserLogin> userLogins = api.getLastUserLogins(u.getId());
			    tempDTO.setCurrentLoginDate(userLogins.get(0).getLoginDate());
                tempDTO.setLastLoginDate(userLogins.get(1).getLoginDate());
            }
            catch (ObjectNotFoundException e) {//Do nothing
            }
			return tempDTO;
			
		}
		return null;
	}
	@Override
	public void toggleActiveUser(Long userId) throws ObjectNotFoundException {
		User user = dao.getEntityById(User.class, userId);
		user.setActive(!user.isActive());
		dao.update(user);
	}

	@Override
	public void setFilePermissions(String uri, Set<PermissionDTO> permissions)
			throws ObjectNotFoundException {
		FileHeaderDTO filedto = getFile(uri);
		FileHeader file = dao.getEntityById(FileHeader.class, filedto.getId());
		if (permissions != null && !permissions.isEmpty()) {
			// Send e-mails to the users that are granted new permissions on the file
			// Delete previous entries.
			for (Permission perm: file.getPermissions())
				dao.delete(perm);
			file.getPermissions().clear();
			for (PermissionDTO dto : permissions) {
				//if (dto.getUser()!=null && dto.getUser().getId().equals(file.getOwner().getId()) && (!dto.hasRead() || !dto.hasWrite() || !dto.hasModifyACL()))
					//throw new InsufficientPermissionsException("Can't remove permissions from owner");
				// Don't include 'empty' permission.
				if (!dto.getRead() && !dto.getWrite() && !dto.getModifyACL()) continue;
				file.addPermission(getPermission(dto));
			}
			dao.flush();
		}
	}

	private Permission getPermission(PermissionDTO dto) throws ObjectNotFoundException {
		Permission res = new Permission();
		if (dto.getGroup() != null)
			res.setGroup(dao.getEntityById(Group.class, dto.getGroup().getId()));
		else if (dto.getUser() != null)
			if (dto.getUser().getId() == null)
				res.setUser(dao.getUser(dto.getUser().getUsername()));
			else
				res.setUser(dao.getEntityById(User.class, dto.getUser().getId()));
		res.setRead(dto.hasRead());
		res.setWrite(dto.hasWrite());
		res.setModifyACL(dto.hasModifyACL());
		return res;
	}

	@Override
	public SystemStatsDTO getSystemStatistics() {
		SystemStatsDTO statistics = new SystemStatsDTO();
		List<UserClass> uclasses = dao.getUserClasses();
		for (UserClass u : uclasses){
			UserClassDTO dto = u.getDTOWithoutUsers();
			SystemStatsDTO stats = new SystemStatsDTO();
			stats.setFileCount(dao.getFileCount(u));
			stats.setFileSize(dao.getFileSize(u));
			stats.setUserCount(dao.getUserCount(u));
			stats.setBandwithUsed(dao.getBandwithUsed(u, new Date()));
			dto.setStatistics(stats);
			statistics.getUserClasses().add(dto);

		}
		Calendar now = Calendar.getInstance();
		now.add(Calendar.DAY_OF_MONTH, -7);
		Date week = now.getTime();
		now=Calendar.getInstance();
		now.add(Calendar.MONTH, -1);
		Date month = now.getTime();
		statistics.setLastMonthUsers(dao.getCountUsersByLastLogin(month));
		statistics.setLastWeekUsers(dao.getCountUsersByLastLogin(week));
		statistics.setFileCount(dao.getFileCount((UserClass)null));
		statistics.setFileSize(dao.getFileSize((UserClass)null));
		statistics.setUserCount(dao.getUserCount((UserClass)null));
		statistics.setBandwithUsed(dao.getBandwithUsed(null, new Date()));
		return statistics;
	}

	@Override
	public List<UserDTO> getLastLoggedInUsers(Date lastLoginDate) {
		List<User> users = dao.getUsersByLastLogin(lastLoginDate);
		List<UserDTO> result = new ArrayList<UserDTO>();
		for (User u : users){			
			UserDTO tempDTO = u.getDTO();
			List<UserLogin> userLogins = dao.getLoginsForUser(u.getId());
			tempDTO.setCurrentLoginDate(userLogins.get(0).getLoginDate());
			tempDTO.setLastLoginDate(userLogins.get(1).getLoginDate());			
			result.add(tempDTO);
		}
		return result;
	}
	
	@Override
	public List<FileBodyDTO> getVersions(Long userId, Long fileId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		User user = dao.getEntityById(User.class, userId);
		FileHeader header = dao.getEntityById(FileHeader.class, fileId);
		List<FileBodyDTO> result = new LinkedList<FileBodyDTO>();
		for(int i = header.getBodies().size()-1 ; i>=0; i--)
			result.add(header.getBodies().get(i).getDTO());
		return result;
	}
	@Override
	public List<UserDTO> getUsersWaitingActivation(){
		List<User> users = dao.getInactiveUsers();
		List<UserDTO> result = new ArrayList<UserDTO>();
		for(User u :users){
			UserDTO tempDTO = u.getDTO();
			List<UserLogin> userLogins = dao.getLoginsForUser(u.getId());
			tempDTO.setCurrentLoginDate(userLogins.get(0).getLoginDate());
			tempDTO.setLastLoginDate(userLogins.get(1).getLoginDate());	
			result.add(tempDTO);
			
		}
		return result;
	}

	@Override
	public void changeUserClass(Long userId, Long userClassId) throws ObjectNotFoundException{
		User user = dao.getEntityById(User.class, userId);
		UserClass userClass = dao.getEntityById(UserClass.class, userClassId);
		user.setUserClass(userClass);
		dao.update(user);
	}

	@Override
	public List<UserClassDTO> getUserClasses(){
		List<UserClassDTO> result = new ArrayList<UserClassDTO>();
		for(UserClass c : dao.getUserClasses())
			result.add(c.getDTOWithoutUsers());
		return result;
	}

	@Override
	public void saveOrUpdateUserClass(UserClassDTO dto) throws ObjectNotFoundException{
		UserClass uclass;
		if(dto.getId()!=null)
			uclass = dao.getEntityById(UserClass.class, dto.getId());
		else
			uclass = new UserClass();
		uclass.setName(dto.getName());
		uclass.setQuota(dto.getQuota());
		if(dto.getId()!=null)
			dao.update(uclass);
		else
			dao.create(uclass);
		dao.flush();

	}

	@Override
	public void removeUserClass(UserClassDTO dto) throws ObjectNotFoundException{
		UserClass uclass = dao.getEntityById(UserClass.class, dto.getId());
		if(uclass==null)
			throw new ObjectNotFoundException("User Class not found");
		dao.delete(uclass);
	}

	@Override
	public List<FileHeaderDTO> searchFileByFilename(String fileName){
		List<FileHeader> files = dao.searchFileByFilename(fileName);
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		for(FileHeader h : files)
			result.add(h.getDTO());
		return result;
	}

	@Override
	public void removeUser(Long userId) throws ObjectNotFoundException, InsufficientPermissionsException{
		User user = api.getUser(userId);
		try{
			FolderDTO folder = getRootFolder(userId);
			deleteFolder(userId, folder.getId());
			List<GroupDTO> groups = getGroups(userId);
			for(GroupDTO group : groups)
				api.deleteGroup(userId, group.getId());
		}
		catch(ObjectNotFoundException e){}
		List<Folder> otherFolders = dao.getSharingFoldersForUser(userId);
		for(Folder f : otherFolders){
			Iterator<Permission> pit = f.getPermissions().iterator();
			while(pit.hasNext()){
				Permission p = pit.next();
				if(p.getUser()!=null&&p.getUser().getId().equals(userId)){
					pit.remove();
					dao.delete(p);
				}
			}
			dao.update(f);
		}
		List<FileHeader> otherFiles = dao.getSharingFilesForUser(userId);
		for(FileHeader f : otherFiles){
			Iterator<Permission> pit = f.getPermissions().iterator();
			while(pit.hasNext()){
				Permission p = pit.next();
				if(p.getUser()!=null&&p.getUser().getId().equals(userId)){
					pit.remove();
					dao.delete(p);
				}
			}
			dao.update(f);
		}
		List<Group> otherGroups = dao.getGroupsContainingUser(userId);
		for(Group g : otherGroups){
			Iterator<User> uit = g.getMembers().iterator();
			while(uit.hasNext()){
				User u = uit.next();
				if(u.getId().equals(userId)){
					uit.remove();
				}
			}
			dao.update(g);
		}
		List<AccountingInfo> infos = dao.getAccountingInfo(user);
		Iterator<AccountingInfo> it = infos.iterator();
		while(it.hasNext()){
			AccountingInfo a = it.next();
			dao.delete(a);
		}
		List<FileUploadStatus> sts = dao.getUploadStatus(userId);
		for(FileUploadStatus s : sts)
			dao.delete(s);
		int deleteCount=dao.deletePermissionsNotCorrespondingToFilesAndFolders(userId);
		
		List<UserLogin> allUserLogins = dao.getAllLoginsForUser(userId);
		for(UserLogin ul : allUserLogins)
			dao.delete(ul);
		dao.flush();
		dao.delete(user);
	}

	/**
	 * Deletes the given folder and all its subfolders and files
	 * Only the permissions for top folder are checked
	 *
	 * @see org.gss_project.gss.server.ejb.ExternalAPI#deleteFolder(java.lang.Long,
	 *      java.lang.Long)
	 */
	public void deleteFolder(final Long userId, final Long folderId) throws ObjectNotFoundException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");

		// Do the actual work.
		final Folder folder = dao.getEntityById(Folder.class, folderId);
		final Folder parent = folder.getParent();
		final User user = dao.getEntityById(User.class, userId);

		removeSubfolderFiles(folder);
		if(parent!=null)
			parent.removeSubfolder(folder);
		dao.delete(folder);
		if(parent!=null)
			touchParentFolders(parent, user, new Date());
	}

	private void removeSubfolderFiles(Folder folder) {
		//remove files for all subfolders
		for (Folder subfolder:folder.getSubfolders())
			removeSubfolderFiles(subfolder);
		//remove this folder's file bodies (actual files)
		for (FileHeader file:folder.getFiles()) {
			for (FileBody body:file.getBodies())
				api.deleteActualFile(body.getStoredFilePath());
			indexFile(file.getId(), true);
		}
	}

	private void touchParentFolders(Folder folder, User modifiedBy, Date modificationDate) {
		Folder f = folder;
		while (f!=null) {
			AuditInfo ai = f.getAuditInfo();
			ai.setModifiedBy(modifiedBy);
			ai.setModificationDate(modificationDate);
			f.setAuditInfo(ai);
			f = f.getParent();
		}
	}

	public void indexFile(Long fileId, boolean delete) {
		Connection qConn = null;
		Session session = null;
		MessageProducer sender = null;
		try {
			Context jndiCtx = new InitialContext();
			ConnectionFactory factory = (QueueConnectionFactory) jndiCtx.lookup("java:/JmsXA");
			Queue queue = (Queue) jndiCtx.lookup("queue/gss-indexingQueue");
			qConn = factory.createConnection();
			session = qConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
			sender = session.createProducer(queue);

			MapMessage map = session.createMapMessage();
			map.setObject("id", fileId);
			map.setBoolean("delete", delete);
			sender.send(map);
		}
		catch (NamingException e) {
			logger.error("Index was not updated: ", e);
		}
		catch (JMSException e) {
			logger.error("Index was not updated: ", e);
		}
		finally {
			try {
				if (sender != null)
					sender.close();
				if (session != null)
					session.close();
				if (qConn != null)
					qConn.close();
			}
			catch (JMSException e) {
				logger.warn(e);
			}
		}
	}

	/*** MOVE METHODS WITH DTOS FROM ExternalAPIAbean ***/
	private FolderDTO getRootFolder(Long userId) throws ObjectNotFoundException {
        if (userId == null)
                throw new ObjectNotFoundException("No user specified");
        Folder folder = dao.getRootFolder(userId);
        return folder.getDTO();
}

	private List<FileHeaderDTO> getFiles(Long userId, Long folderId, boolean ignoreDeleted)
    throws ObjectNotFoundException, InsufficientPermissionsException {
		// Validate.
		if (userId == null)
		    throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
		    throw new ObjectNotFoundException("No folder specified");
		User user = dao.getEntityById(User.class, userId);
		Folder folder = dao.getEntityById(Folder.class, folderId);
		if (!folder.hasReadPermission(user))
		    throw new InsufficientPermissionsException("You don't have the permissions to read this folder");
		// Do the actual work.
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		List<FileHeader> files = dao.getFiles(folderId, userId, ignoreDeleted);
		for (FileHeader f : files)
		    result.add(f.getDTO());
		return result;
		}
	
	private List<GroupDTO> getGroups(final Long userId) throws ObjectNotFoundException {
        if (userId == null)
                throw new ObjectNotFoundException("No user specified");
        final List<Group> groups = dao.getGroups(userId);
        final List<GroupDTO> result = new ArrayList<GroupDTO>();
        for (final Group g : groups)
                result.add(g.getDTO());
        return result;
	}
}
