/*
 * Copyright 2007, 2008, 2009, 2010 Electronic Business Systems Ltd.
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
package gr.ebs.gss.server.ejb;

import static gr.ebs.gss.server.configuration.GSSConfigurationFactory.getConfiguration;
import gr.ebs.gss.client.exceptions.DuplicateNameException;
import gr.ebs.gss.client.exceptions.GSSIOException;
import gr.ebs.gss.client.exceptions.InsufficientPermissionsException;
import gr.ebs.gss.client.exceptions.InvitationUsedException;
import gr.ebs.gss.client.exceptions.ObjectNotFoundException;
import gr.ebs.gss.client.exceptions.QuotaExceededException;
import gr.ebs.gss.server.domain.AuditInfo;
import gr.ebs.gss.server.domain.FileBody;
import gr.ebs.gss.server.domain.FileHeader;
import gr.ebs.gss.server.domain.FileUploadStatus;
import gr.ebs.gss.server.domain.Folder;
import gr.ebs.gss.server.domain.Group;
import gr.ebs.gss.server.domain.Invitation;
import gr.ebs.gss.server.domain.Nonce;
import gr.ebs.gss.server.domain.Permission;
import gr.ebs.gss.server.domain.User;
import gr.ebs.gss.server.domain.UserClass;
import gr.ebs.gss.server.domain.dto.FileBodyDTO;
import gr.ebs.gss.server.domain.dto.FileHeaderDTO;
import gr.ebs.gss.server.domain.dto.FolderDTO;
import gr.ebs.gss.server.domain.dto.GroupDTO;
import gr.ebs.gss.server.domain.dto.PermissionDTO;
import gr.ebs.gss.server.domain.dto.StatsDTO;
import gr.ebs.gss.server.domain.dto.UserDTO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPAttributeSet;
import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPException;

/**
 * The concrete implementation of the ExternalAPI interface.
 *
 * @author past
 */
@Singleton
public class ExternalAPIBean implements ExternalAPI {
	/**
	 * The default MIME type for files without an explicit one.
	 */
	private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

	/**
	 * The size of the buffer that is used to temporarily store chunks of
	 * uploaded files, while storing them to the file repository.
	 */
	private static final int UPLOAD_BUFFER_SIZE = 1024 * 4;

	/**
	 * The logger.
	 */
	private static Log logger = LogFactory.getLog(ExternalAPIBean.class);

	/**
	 * Injected reference to the UserDAO.
	 */
	@Inject
	private UserDAO userDao;

	/**
	 * Injected reference to the UserClassDAO.
	 */
	@Inject
	private UserClassDAO userClassDao;

	/**
	 * Injected reference to the FolderDAO.
	 */
	@Inject
	private FolderDAO folderDao;

	/**
	 * Injected reference to the FileDAO.
	 */
	@Inject
	private FileDAO fileDao;

	/**
	 * Injected reference to the AccountingDAO.
	 */
	@Inject
	private AccountingDAO accountingDao;

	/**
	 * Injected reference to the FileUploadDAO.
	 */
	@Inject
	private FileUploadDAO fileUploadDao;

	/**
	 * Injected reference to the GroupDAO.
	 */
	@Inject
	private GroupDAO groupDao;

	/**
	 * Injected reference to the transaction handler.
	 */
	@Inject
	private Transaction transaction;

	// TODO Remove after migration to Morphia is complete.
	private GSSDAO dao;

	/**
	 * A cached random number generator for creating unique filenames.
	 */
	private static Random random = new Random();

	/**
	 * Mark the folder and all of its parent folders as modified from the specified user.
	 */
	private void touchParentFolders(Folder folder, User user, Date date) {
		Folder f = folder;
		while (f != null) {
			AuditInfo ai = f.getAuditInfo();
			ai.setModifiedBy(user);
			ai.setModificationDate(date);
			f.setAuditInfo(ai);
			transaction.save(f);
			f = f.getParent();
		}
	}

	@Override
	public FolderDTO getRootFolder(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		Folder folder = folderDao.getRootFolder(userDao.get(userId));
		return folder.getDTO();
	}

	@Override
	public FolderDTO getFolder(final Long userId, final Long folderId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		final User user = dao.getEntityById(User.class, userId);
		final Folder folder = dao.getEntityById(Folder.class, folderId);
		// Check permissions
		if (!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the permissions to read this folder");
		return folder.getDTO();
	}

	@Override
	public User getUser(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		return userDao.get(userId);
	}

	@Override
	public GroupDTO getGroup(Long userId, String name) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (name == null)
			throw new ObjectNotFoundException("No group specified");
		User user = userDao.get(userId);
		List<Group> groups = user.getGroupsSpecified();
		for (Group group: groups)
			if (group.getName().equals(name))
				return group.getDTO();
		throw new ObjectNotFoundException("Group " + name + " not found");
	}

	@Override
	public List<GroupDTO> getGroups(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		List<Group> groups = user.getGroupsSpecified();
		List<GroupDTO> result = new ArrayList<GroupDTO>();
		for (Group g : groups)
			result.add(g.getDTO());
		return result;
	}

	@Override
	public List<FileHeaderDTO> getFiles(Long userId, Long folderId, boolean ignoreDeleted)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = userDao.get(userId);
		Folder folder = folderDao.get(folderId);
		if (!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the permissions to read this folder");
		// Do the actual work.
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		List<FileHeader> files = folder.getFiles();
		for (FileHeader f : files)
			if (f.hasReadPermission(user) && (!ignoreDeleted || !f.isDeleted()))
				result.add(f.getDTO());
		return result;
	}

	@Override
	public List<UserDTO> getUsers(final Long userId, final Long groupId) throws ObjectNotFoundException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (groupId == null)
			throw new ObjectNotFoundException("No group specified");

		// Do the actual work.
		final List<User> users = dao.getUsers(groupId);
		final List<UserDTO> result = new ArrayList<UserDTO>();
		for (final User u : users)
			result.add(u.getDTO());
		return result;
	}

	@Override
	public FolderDTO createFolder(Long userId, Long parentId, String name)
			throws DuplicateNameException, ObjectNotFoundException, InsufficientPermissionsException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("New folder name is empty");
		if (parentId == null)
			throw new ObjectNotFoundException("No parent specified");
		if (folderDao.exists(parentId, name))
			throw new DuplicateNameException("A folder with the name '" + name +
						"' already exists at this level");
		if (fileDao.exists(parentId, name))
			throw new DuplicateNameException("A file with the name '" + name +
						"' already exists at this level");

		User creator = userDao.get(userId);

		Folder parent = folderDao.get(parentId);
		if (parent == null)
			throw new ObjectNotFoundException("Parent folder not found");
		if (!parent.hasWritePermission(creator))
			throw new InsufficientPermissionsException("You don't have the permissions" +
					" to write to this folder");

		// Do the actual work.
		FolderDTO folder = createFolder(name, parent, creator);
		transaction.commit();
		return folder;
	}

	/**
	 * Create a new folder with the provided name, parent and owner.
	 *
	 * @param name
	 * @param parent
	 * @param creator
	 * @return the new folder
	 */
	private FolderDTO createFolder(String name, Folder parent, User creator) {
		Folder folder = new Folder();
		folder.setName(name);
		if (parent != null) {
			parent.addSubfolder(folder);
			folder.setOwner(parent.getOwner());
		} else
			folder.setOwner(creator);

		Date now = new Date();
		AuditInfo auditInfo = new AuditInfo();
		auditInfo.setCreatedBy(creator);
		auditInfo.setCreationDate(now);
		auditInfo.setModifiedBy(creator);
		auditInfo.setModificationDate(now);
		folder.setAuditInfo(auditInfo);
		touchParentFolders(folder, auditInfo.getModifiedBy(), auditInfo.getModificationDate());

		if (parent != null)
			for (Permission p : parent.getPermissions()) {
				Permission permission = new Permission();
				permission.setGroup(p.getGroup());
				permission.setUser(p.getUser());
				permission.setRead(p.getRead());
				permission.setWrite(p.getWrite());
				permission.setModifyACL(p.getModifyACL());
				folder.addPermission(permission);
			}
		else {
			Permission permission = new Permission();
			permission.setUser(creator);
			permission.setRead(true);
			permission.setWrite(true);
			permission.setModifyACL(true);
			folder.addPermission(permission);
		}
		transaction.save(folder);
		return folder.getDTO();
	}

	@Override
	public void deleteFolder(Long userId, Long folderId)
			throws InsufficientPermissionsException, ObjectNotFoundException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");

		// Do the actual work.
		Folder folder = folderDao.get(folderId);
		Folder parent = folder.getParent();
		if (parent == null)
			throw new ObjectNotFoundException("Deleting the root folder is not allowed");
		User user = userDao.get(userId);
		if (!folder.hasDeletePermission(user)) {
			logger.info("User " + user.getId() + " cannot delete folder " +
						folder.getName() + "(" + folder.getId() + ")");
			throw new InsufficientPermissionsException("User " + user.getId() +
						" cannot delete folder " + folder.getName() + "(" +
						folder.getId() + ")");
		}
		removeSubfolderFiles(folder);
		parent.removeSubfolder(folder);
		folderDao.delete(folder);
		touchParentFolders(parent, user, new Date());
	}

	/**
	 * Traverses the folder and deletes all actual files (file system)
	 * regardless of permissions
	 *
	 * @param folder
	 */
	private void removeSubfolderFiles(Folder folder) {
		//remove files for all subfolders
		for (Folder subfolder:folder.getSubfolders())
			removeSubfolderFiles(subfolder);
		//remove this folder's file bodies (actual files)
		for (FileHeader file:folder.getFiles()) {
			for (FileBody body:file.getBodies())
				deleteActualFile(body.getStoredPath());
			indexFile(file.getId(), true);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<FolderDTO> getSubfolders(Long userId, Long folderId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = dao.getEntityById(User.class, userId);
		Folder folder = dao.getEntityById(Folder.class, folderId);
		if (!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the permissions to read this folder");
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		if (folder.hasReadPermission(user))
			for (Folder f : folder.getSubfolders())
				if (f.hasReadPermission(user) && !f.isDeleted())
					result.add(f.getDTO());
		return result;
	}

	@Override
	public FolderDTO updateFolder(Long userId, Long folderId, String folderName,
				Boolean readForAll,
				Set<PermissionDTO> permissions)
			throws InsufficientPermissionsException, ObjectNotFoundException,
			DuplicateNameException {

		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");

		Folder folder = folderDao.get(folderId);
		User user = userDao.get(userId);
		if (folderName != null && !folder.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		if (permissions != null && !permissions.isEmpty() && !folder.hasModifyACLPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		// Check permissions for making file public.
		if (readForAll != null && !user.equals(folder.getOwner()))
				throw new InsufficientPermissionsException("Only the owner can make a folder public or not public");

		Folder parent = folder.getParent();
		if (folderName != null) {
			if (parent != null) {
				if (folderDao.exists(parent.getId(), folderName))
					throw new DuplicateNameException("A folder with the name '" + folderName +
								"' already exists at this level");
				if (fileDao.exists(parent.getId(), folderName))
					throw new DuplicateNameException("A file with the name '" + folderName +
								"' already exists at this level");
			}
			// Do the actual modification.
			folder.setName(folderName);
		}
		if (permissions != null)
			setFolderPermissions(user, folder, permissions);
		if (readForAll != null && user.equals(folder.getOwner()))
			if(!readForAll)
				folder.setReadForAll(readForAll);
			else{
				List<FileHeader> files = folder.getFiles();
				for (FileHeader f : files) {
					f.setReadForAll(readForAll);
					transaction.save(f);
				}
				folder.setReadForAll(readForAll);
			}
		folder.getAuditInfo().setModificationDate(new Date());
		folder.getAuditInfo().setModifiedBy(user);
		transaction.save(folder);
		touchParentFolders(folder, user, new Date());
		transaction.commit();
		return folder.getDTO();
	}

	@Override
	public void createGroup(Long userId, String name) throws ObjectNotFoundException, DuplicateNameException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("New group name is empty");
		if (name.indexOf('/')>=0)
			throw new IllegalArgumentException("Character '/' is not allowed in group name");

		User owner = userDao.get(userId);
		if (groupDao.existsGroup(owner, name))
			throw new DuplicateNameException("A group with the name '" + name + "' already exists");
		// Do the actual work.
		Group group = owner.createGroup(name);
		transaction.save(group);
		transaction.save(owner);
		transaction.commit();
	}

	@Override
	public void deleteGroup(Long userId, Long groupId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (groupId == null)
			throw new ObjectNotFoundException("No group specified");

		// Do the actual work.
		User owner = userDao.get(userId);
		Group group = groupDao.get(groupId);
		// Only delete the group if actually owned by the user.
		if (group.getOwner().equals(owner)) {
			List<Folder> folders = folderDao.getFoldersPermittedForGroup(owner, group);
			for (Folder folder : folders) {
				boolean dirty = false;
				for (Iterator<Permission> it = folder.getPermissions().iterator(); it.hasNext(); )
					if (group.equals(it.next().getGroup())) {
						it.remove();
						dirty = true;
					}
				for (FileHeader file : folder.getFiles()) {
					boolean fDirty = false;
					for (Iterator<Permission> it = file.getPermissions().iterator(); it.hasNext(); )
						if (group.equals(it.next().getGroup())) {
							it.remove();
							fDirty = true;
							dirty = true;
						}
					if (fDirty)
						transaction.save(file);
				}
				if (dirty)
					transaction.save(folder);
			}
			List<FileHeader> files = fileDao.getSharedFilesNotInSharedFolders(owner);
			for (FileHeader h : files) {
				boolean dirty = false;
				for (Iterator<Permission> it = h.getPermissions().iterator(); it.hasNext(); )
					if (group.equals(it.next().getGroup())) {
						it.remove();
						dirty = true;
					}
				if (dirty)
					transaction.save(h);
			}
			for (User member: group.getMembers())
				// The owner will be saved later.
				if (!owner.equals(member)) {
					member.removeFromGroup(group);
					transaction.save(member);
				} else
					owner.removeFromGroup(group);
			groupDao.removeGroup(group);
			owner.removeGroup(group);
			transaction.save(owner);
			transaction.commit();
		} else
			throw new InsufficientPermissionsException("You are not the owner of this group");
	}

	@Override
	public FileHeader createFile(Long userId, Long folderId, String name, String mimeType, InputStream stream)
			throws DuplicateNameException, ObjectNotFoundException, GSSIOException,
			InsufficientPermissionsException, QuotaExceededException {
		File file = null;
		try {
			file = uploadFile(stream, userId);
		} catch ( IOException ioe) {
			// Supply a more accurate problem description.
			throw new GSSIOException("Problem creating file",ioe);
		}
		return createFile(userId, folderId, name, mimeType, file.length(), file.getAbsolutePath());
	}

	@Override
	public void indexFile(Long fileId, boolean delete) {
		return;/*
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
		}*/
	}



	/**
	 * A helper method that generates a unique file path for a stored file. The
	 * files are stored using random hash names that are distributed evenly in
	 * a 2-level tree of subdirectories named after the first two hex characters
	 * in the name. For example, file ab1234cd5769f will be stored in the path
	 * /file-repository-root/a/b/ab1234cd5769f. The directories will be created
	 * if they don't already exist.
	 *
	 * @return a unique new file path
	 */
	private String generateRepositoryFilePath() {
		String filename = Long.toHexString(random.nextLong());
		String fileRepositoryPath = getConfiguration().getString("fileRepositoryPath","/tmp");
		File root = new File(fileRepositoryPath);
		if (!root.exists())
			root.mkdirs();
		File firstFolder = new File(root + File.separator + filename.substring(0, 1));
		if (!firstFolder.exists())
			firstFolder.mkdir();
		File secondFolder = new File(firstFolder + File.separator + filename.substring(1, 2));
		if (!secondFolder.exists())
			secondFolder.mkdir();
		return secondFolder + File.separator + filename;
	}

	@Override
	public void deleteFile(Long userId, Long fileId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");

		// Do the actual work.
		FileHeader file = fileDao.get(fileId);
		Folder parent = file.getFolder();
		if (parent == null)
			throw new ObjectNotFoundException("The specified file has no parent folder");
		User user = userDao.get(userId);
		if (!file.hasDeletePermission(user))
			throw new InsufficientPermissionsException("User " + user.getId() +
						" cannot delete file " + file.getName() + "(" +
						file.getId() + ")");
		for (FileBody body : file.getBodies())
			deleteActualFile(body.getStoredPath());
		file.getFolder().removeFile(file);
		// The modified parent folder will be persisted in touchParentFolders below.
		fileDao.delete(file);
		touchParentFolders(parent, user, new Date());
		indexFile(fileId, true);
	}

	@Override
	public void deleteActualFile(String path) {
		if (path == null)
			return;
		File file = new File(path);
		if (!file.delete())
			logger.error("Could not delete file " + path);
	}

	@Override
	public void createTag(Long userId, Long fileHeaderId, String tag)
			throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileHeaderId == null)
			throw new ObjectNotFoundException("No file specified");
		if (StringUtils.isEmpty(tag))
			throw new ObjectNotFoundException("Tag is empty");

		User user = userDao.get(userId);
		FileHeader file = fileDao.get(fileHeaderId);
		Folder folder = file.getFolder();
		if (folder == null)
			throw new ObjectNotFoundException("The specified file has no parent folder");
		if (!user.getTags().contains(tag)) {
			user.addTag(tag);
			transaction.save(user);
		}
		file.addTag(tag);
		transaction.save(file);
		touchParentFolders(folder, user, new Date());
		transaction.commit();
	}

	@Override
	public List<String> getUserTags(Long userId) throws ObjectNotFoundException {
		User user = userDao.get(userId);
		if (user == null)
			throw new ObjectNotFoundException("User not found");
		return user.getTags();
	}

	@Override
	public void updateFile(Long userId, Long fileId, String name,
				String tagSet, Date modificationDate, Boolean versioned,
				Boolean readForAll,	Set<PermissionDTO> permissions)
			throws DuplicateNameException, ObjectNotFoundException,	InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		FileHeader file = fileDao.get(fileId);
		final Folder parent = file.getFolder();
		if (parent == null)
			throw new ObjectNotFoundException("The specified file has no parent folder");

		User user = userDao.get(userId);
		// Check permissions for modifying the file metadata.
		if ((name != null || tagSet != null || modificationDate != null || versioned != null) && !file.hasWritePermission(user))
			throw new InsufficientPermissionsException("User " + user.getId() +	" cannot update file " + file.getName() + "(" +	file.getId() + ")");
		// Check permissions for making file public.
		if (readForAll != null && !user.equals(file.getOwner()))
				throw new InsufficientPermissionsException("Only the owner can make a file public or not public");
		// Check permissions for modifying the ACL.
		if(permissions != null && !permissions.isEmpty() &&	!file.hasModifyACLPermission(user))
			throw new InsufficientPermissionsException("User " + user.getId() +	" cannot update the permissions on file " +	file.getName() + "(" + file.getId() + ")");

		if (name != null) {
			// Do plain check for file already exists.
			// Extreme concurrency case should be caught by constraint violation later.
			if (folderDao.exists(parent.getId(), name))
				throw new DuplicateNameException("A folder with the name '" + name +
							"' already exists at this level");
			if (fileDao.exists(parent.getId(), name))
				throw new DuplicateNameException("A file with the name '" + name +
							"' already exists at this level");
			file.setName(name);
		}

		if (modificationDate != null)
			file.getAuditInfo().setModificationDate(modificationDate);
		else
			file.getAuditInfo().setModificationDate(new Date());
		file.getAuditInfo().setModifiedBy(user);

		if (tagSet != null) {
			// Since updated tags come wholesale, we have to remove the old
			// ones first and then add the new ones to both user and file.
			user.removeTags(file.getTags());
			file.clearTags();
			StringTokenizer st = new StringTokenizer(tagSet, ",");
			while (st.hasMoreTokens()) {
				String tag = st.nextToken().trim();
				file.addTag(tag);
				user.addTag(tag);
			}
			transaction.save(user);
		}
		if (versioned != null && !file.isVersioned() == versioned) {
			if (file.isVersioned())
				removeOldVersions(user, file);
			file.setVersioned(versioned);
		}
		if (readForAll != null && user.equals(file.getOwner()))
			file.setReadForAll(readForAll);
		if (permissions != null && !permissions.isEmpty())
			setFilePermissions(file, permissions);
		transaction.save(file);
		/*
		 * XXX: implement this in terms of MongoDB.
		 * Force constraint violation to manifest itself here.
		 * This should cover extreme concurrency cases that the simple check
		 * above hasn't caught.
		 */
		/*try {
			dao.flush();
		}
		catch (EJBTransactionRolledbackException e) {
			Throwable cause = e.getCause();
			if (cause instanceof PersistenceException && cause.getCause() instanceof ConstraintViolationException)
				throw new DuplicateNameException("A file or folder with the name '" + name + "' already exists");
			throw e;
		}*/

		touchParentFolders(parent, user, new Date());
		transaction.commit();

		// Re-index the file if it was modified.
		if (name != null || tagSet != null)
			indexFile(fileId, false);
	}

	@Override
	public InputStream getFileContents(Long userId, Long fileId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");

		FileHeader header = fileDao.get(fileId);
		User user = userDao.get(userId);
		if (!header.hasReadPermission(user)) {
			logger.info("User " + user.getId() + " cannot read file " +
						header.getName() + "(" + fileId + ")");
			throw new InsufficientPermissionsException("You don't have the " +
					"necessary permissions");
		}

		File f = new File(header.getCurrentBody().getStoredPath());
		try {
			return new FileInputStream(f);
		} catch (FileNotFoundException e) {
			logger.error("Could not locate the contents of file " + f.getAbsolutePath());
			throw new ObjectNotFoundException("The file contents could not be located");
		}
	}

	@Override
	public InputStream getFileContents(Long userId, Long fileId, Long bodyId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (bodyId == null)
			throw new ObjectNotFoundException("No file specified");

		FileHeader header = fileDao.get(fileId);
		FileBody body = null;
		for (FileBody b: header.getBodies())
			if (b.getId().equals(bodyId))
				body = b;
		if (body == null)
			throw new ObjectNotFoundException("Body not found");
		User user = userDao.get(userId);
		if (!header.hasReadPermission(user)) {
			logger.info("User " + user.getId() + " cannot read file " +
					header.getName() + "(" + fileId + ")");
			throw new InsufficientPermissionsException("You don't have the " +
					"necessary permissions");
		}

		File f = new File(body.getStoredPath());
		try {
			return new FileInputStream(f);
		} catch (FileNotFoundException e) {
			logger.error("Could not locate the contents of file " +
					f.getAbsolutePath());
			throw new ObjectNotFoundException("The file contents could not " +
					"be located");
		}
	}

	@Override
	public FileHeaderDTO getFile(Long userId, Long fileId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		final User user = dao.getEntityById(User.class, userId);
		final FileHeader file = dao.getEntityById(FileHeader.class, fileId);
		if (!file.hasReadPermission(user) && !file.getFolder().hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		return file.getDTO();
	}

	@Override
	public FileBodyDTO getFileBody(Long userId, Long fileId, Long bodyId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		User user = dao.getEntityById(User.class, userId);
		FileHeader file = dao.getEntityById(FileHeader.class, fileId);
		if (!file.hasReadPermission(user) && !file.getFolder().hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		FileBody body = dao.getEntityById(FileBody.class, bodyId);
		return body.getDTO();
	}

	@Override
	public Object getResourceAtPath(Long ownerId, String path, boolean ignoreDeleted)
			throws ObjectNotFoundException {
		if (ownerId == null)
			throw new ObjectNotFoundException("No user specified");
		if (StringUtils.isEmpty(path))
			throw new ObjectNotFoundException("No path specified");

		User owner = userDao.get(ownerId);
		List<String> pathElements = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(path, "/");
		while (st.hasMoreTokens())
			pathElements.add(st.nextToken());
		if (pathElements.size() < 1)
			return getRootFolder(owner.getId());
		// Store the last element, since it requires special handling.
		String lastElement = pathElements.remove(pathElements.size() - 1);
		FolderDTO cursor = getRootFolder(owner.getId());
		// Traverse and verify the specified folder path.
		for (String pathElement : pathElements) {
			cursor = getFolder(cursor.getId(), pathElement);
			if (cursor.isDeleted())
				throw new ObjectNotFoundException("Folder " + cursor.getPath() + " not found");
		}

		// Use the lastElement to retrieve the actual resource.
		Object resource = null;
		try {
			FileHeaderDTO file = getFile(cursor.getId(), lastElement);
			if (ignoreDeleted && file.isDeleted())
				throw new ObjectNotFoundException("Resource not found");
			resource = file;
		} catch (ObjectNotFoundException e) {
			// Perhaps the requested resource is not a file, so
			// check for folders as well.
			FolderDTO folder = getFolder(cursor.getId(), lastElement);
			if (ignoreDeleted && folder.isDeleted())
				throw new ObjectNotFoundException("Resource not found");
			resource = folder;
		}
		return resource;
	}

	/**
	 * Retrieve a file for the specified user that has the specified name and
	 * its parent folder has id equal to folderId.
	 *
	 * @param userId the ID of the current user
	 * @param folderId the ID of the parent folder
	 * @param name the name of the requested file
	 * @return the file found
	 * @throws ObjectNotFoundException if the specified folder or file was not
	 *             found, with the exception message mentioning the precise
	 *             problem
	 */
	private FileHeaderDTO getFile(Long folderId, String name) throws ObjectNotFoundException {
		if (folderId == null)
			throw new ObjectNotFoundException("No parent folder specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("No file specified");

		FileHeader file = fileDao.get(folderId, name);
		return file.getDTO();
	}

	/**
	 * Retrieve a folder for the specified user that has the specified name and
	 * its parent folder has id equal to parentId.
	 *
	 * @param parentId the ID of the parent folder
	 * @param name the name of the requested folder
	 * @return the folder found
	 * @throws ObjectNotFoundException if the specified folder or parent was not
	 *             found, with the exception message mentioning the precise
	 *             problem
	 */
	private FolderDTO getFolder(Long parentId, String name) throws ObjectNotFoundException {
		if (parentId == null)
			throw new ObjectNotFoundException("No parent folder specified");
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("No folder specified");

		Folder folder = folderDao.getFolder(parentId, name);
		return folder.getDTO();
	}

	private FileHeaderDTO updateFileContents(Long userId, Long fileId, String mimeType, InputStream resourceInputStream) throws ObjectNotFoundException, GSSIOException, InsufficientPermissionsException, QuotaExceededException {
		File file = null;
		try {
			file = uploadFile(resourceInputStream, userId);
		} catch ( IOException ioe) {
			// Supply a more accurate problem description.
			throw new GSSIOException("Problem creating file",ioe);
		}
		return updateFileContents(userId, fileId, mimeType, file.length(), file.getAbsolutePath());
	}

	@Override
	public void copyFile(Long userId, Long fileId, String dest) throws ObjectNotFoundException, DuplicateNameException, GSSIOException, InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(userId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination parent folder not found");
		FolderDTO parent = (FolderDTO) destination;
		copyFile(userId, fileId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void copyFileToPath(Long userId, Long ownerId, Long fileId, String dest) throws ObjectNotFoundException, DuplicateNameException, GSSIOException, InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(ownerId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination parent folder not found");
		FolderDTO parent = (FolderDTO) destination;
		copyFile(userId, fileId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void copyFile(Long userId, Long fileId, Long destId, String destName)
			throws ObjectNotFoundException, DuplicateNameException, GSSIOException,
			InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (destId == null)
			throw new ObjectNotFoundException("No destination specified");
		if (StringUtils.isEmpty(destName))
			throw new ObjectNotFoundException("No destination file name specified");

		FileHeader file = fileDao.get(fileId);
		Folder destination = folderDao.get(destId);
		User user = userDao.get(userId);
		if (!file.hasReadPermission(user) || !destination.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		boolean versioned = file.isVersioned();
		int versionsNumber = file.getBodies().size();
		FileBody oldestBody = file.getBodies().get(0);
		assert oldestBody != null;
		File contents = new File(oldestBody.getStoredPath());
		try {
			FileHeader copiedFile = createFile(user.getId(), destination.getId(), destName, oldestBody.getMimeType(), new FileInputStream(contents));
			copiedFile.setVersioned(versioned);
			transaction.save(copiedFile);
			if (versionsNumber > 1)
				for (int i = 1; i < versionsNumber; i++) {
					FileBody body = file.getBodies().get(i);
					assert body != null;
					contents = new File(body.getStoredPath());
					updateFileContents(user.getId(), copiedFile.getId(), body.getMimeType(), new FileInputStream(contents));
				}
			for (String tag : file.getTags())
				createTag(userId, copiedFile.getId(), tag);
			transaction.commit();
		} catch (FileNotFoundException e) {
			throw new ObjectNotFoundException("File contents not found for file " + contents.getAbsolutePath());
		}
	}

	@Override
	public void copyFolder(Long userId, Long folderId, String dest) throws ObjectNotFoundException, DuplicateNameException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(userId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination folder not found");
		FolderDTO parent = (FolderDTO) destination;
		copyFolder(userId, folderId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void copyFolder(Long userId, Long folderId, Long destId, String destName) throws ObjectNotFoundException, DuplicateNameException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		if (destId == null)
			throw new ObjectNotFoundException("No destination specified");
		if (StringUtils.isEmpty(destName))
			throw new ObjectNotFoundException("No destination folder name specified");
		Folder folder = dao.getEntityById(Folder.class, folderId);
		Folder destination = dao.getEntityById(Folder.class, destId);
		User user = dao.getEntityById(User.class, userId);
		if (!destination.hasWritePermission(user) || !folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		createFolder(user.getId(), destination.getId(), destName);
	}

	@Override
	public void copyFolderStructureToPath(Long userId, Long ownerId, Long folderId, String dest) throws ObjectNotFoundException, DuplicateNameException, InsufficientPermissionsException, GSSIOException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(ownerId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination folder not found");
		FolderDTO parent = (FolderDTO) destination;
		copyFolderStructure(userId, folderId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void copyFolderStructure(Long userId, Long folderId, Long destId, String destName) throws ObjectNotFoundException, DuplicateNameException, InsufficientPermissionsException, GSSIOException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		if (destId == null)
			throw new ObjectNotFoundException("No destination specified");
		if (StringUtils.isEmpty(destName))
			throw new ObjectNotFoundException("No destination folder name specified");

		Folder folder = dao.getEntityById(Folder.class, folderId);
		Folder destination = dao.getEntityById(Folder.class, destId);
		final User user = dao.getEntityById(User.class, userId);
		// XXX: quick fix need to copy only visible items to user (Source
		// for bugs)
		if (!folder.getOwner().getId().equals(userId) && !folder.hasReadPermission(user))
			return;
		if(folder.isDeleted())//do not copy trashed folder and contents
			return;
		if (!destination.hasWritePermission(user) || !folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		createFolder(user.getId(), destination.getId(), destName);
		Folder createdFolder = folderDao.getFolder(destination.getId(), destName);
		List<FileHeader> files = folder.getFiles();
		if (files != null)
			for (FileHeader file : files)
				if(!file.isDeleted())
					copyFile(userId, file.getId(), createdFolder.getId(), file.getName());
		List<Folder> subFolders = folder.getSubfolders();
		if (subFolders != null)
			for (Folder sub : subFolders)
				if(!sub.getId().equals(createdFolder.getId()))
					copyFolderStructure(userId, sub.getId(), createdFolder.getId(), sub.getName());

	}

	/**
	 * For a provided path, remove the last element and return the rest, that is
	 * the path of the parent folder.
	 *
	 * @param path the specified path
	 * @return the path of the parent folder
	 * @throws ObjectNotFoundException if the provided string contains no path
	 *             delimiters
	 */
	private String getParentPath(String path) throws ObjectNotFoundException {
		int lastDelimiter = path.lastIndexOf('/');
		if (lastDelimiter == 0)
			return "/";
		if (lastDelimiter == -1)
			// No path found.
			throw new ObjectNotFoundException("There is no parent in the path: " + path);
		else if (lastDelimiter < path.length() - 1)
			// Return the part before the delimiter.
			return path.substring(0, lastDelimiter);
		else {
			// Remove the trailing delimiter and then recurse.
			String strippedTrail = path.substring(0, lastDelimiter);
			return getParentPath(strippedTrail);
		}
	}

	/**
	 * Get the last element in a path that denotes the file or folder name.
	 *
	 * @param path the provided path
	 * @return the last element in the path
	 */
	private String getLastElement(String path) {
		int lastDelimiter = path.lastIndexOf('/');
		if (lastDelimiter == -1)
			// No path found.
			return path;
		else if (lastDelimiter < path.length() - 1)
			// Return the part after the delimiter.
			return path.substring(lastDelimiter + 1);
		else {
			// Remove the trailing delimiter and then recurse.
			String strippedTrail = path.substring(0, lastDelimiter);
			return getLastElement(strippedTrail);
		}
	}

	@Override
	public void moveFileToTrash(Long userId, Long fileId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");

		// Do the actual work.
		FileHeader file = dao.getEntityById(FileHeader.class, fileId);
		Folder parent = file.getFolder();
		if (parent == null)
			throw new ObjectNotFoundException("The specified file has no parent folder");
		User user = dao.getEntityById(User.class, userId);
		if (!file.hasDeletePermission(user))
			throw new InsufficientPermissionsException("User " + user.getId() + " cannot delete file " + file.getName() + "(" + file.getId() + ")");

		file.setDeleted(true);
		dao.update(file);
		touchParentFolders(parent, user, new Date());
	}

	@Override
	public void moveFileToPath(Long userId, Long ownerId, Long fileId, String dest) throws ObjectNotFoundException, InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(ownerId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination parent folder not found");
		FolderDTO parent = (FolderDTO) destination;
		moveFile(userId, fileId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void moveFile(Long userId, Long fileId, Long destId, String destName)
			throws InsufficientPermissionsException, ObjectNotFoundException,
			QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (destId == null)
			throw new ObjectNotFoundException("No destination specified");
		if (StringUtils.isEmpty(destName))
			throw new ObjectNotFoundException("No destination file name specified");

		FileHeader file = fileDao.get(fileId);
		Folder source = file.getFolder();
		Folder destination = folderDao.get(destId);

		User owner = userDao.get(userId);
		if (!file.hasDeletePermission(owner) ||
				!destination.hasWritePermission(owner))
			throw new InsufficientPermissionsException("User " +
					owner.getId() + " cannot move file " + file.getName() +
					"(" + file.getId() + ")");

		// If the destination folder belongs to another user:
		if (!file.getOwner().equals(destination.getOwner())) {
			// (a) check if the destination quota allows the move
			if (getQuotaLeft(destination.getOwner().getId()) < file.getTotalSize())
				throw new QuotaExceededException("Not enough free space available");
			User newOwner = destination.getOwner();
			// (b) if quota OK, change the owner of the file
			file.setOwner(newOwner);
			// if the file has no permission for the new owner, add it
			Permission ownerPermission = null;
			for (Permission p : file.getPermissions())
				if (p.getUser() != null)
					if (p.getUser().equals(newOwner)) {
						ownerPermission = p;
						break;
					}
			if (ownerPermission == null) {
				ownerPermission = new Permission();
				ownerPermission.setUser(newOwner);
				file.addPermission(ownerPermission);
			}
			ownerPermission.setRead(true);
			ownerPermission.setWrite(true);
			ownerPermission.setModifyACL(true);
		}
		Date now = new Date();
		AuditInfo auditInfo = new AuditInfo();
		auditInfo.setModifiedBy(owner);
		auditInfo.setModificationDate(now);
		file.setAuditInfo(auditInfo);
		// Move the file to the destination folder.
		destination.addFile(file);
		transaction.save(file);
		// Source and destination folders will be persisted in the following calls.
		touchParentFolders(source, owner, now);
		touchParentFolders(destination, owner, now);
		transaction.commit();
	}

	@Override
	public void moveFolderToPath(Long userId, Long ownerId, Long folderId, String dest) throws ObjectNotFoundException, InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		if (StringUtils.isEmpty(dest))
			throw new ObjectNotFoundException("No destination specified");

		Object destination = getResourceAtPath(ownerId, getParentPath(dest), true);
		if (!(destination instanceof FolderDTO))
			throw new ObjectNotFoundException("Destination parent folder not found");
		FolderDTO parent = (FolderDTO) destination;
		moveFolder(userId, folderId, parent.getId(), getLastElement(dest));
	}

	@Override
	public void moveFolder(Long userId, Long folderId, Long destId, String destName)
			throws ObjectNotFoundException, InsufficientPermissionsException,
			QuotaExceededException {
		Folder source = dao.getEntityById(Folder.class, folderId);
		Folder destination = dao.getEntityById(Folder.class, destId);
		User user = dao.getEntityById(User.class, userId);
		User sourceOwner = source.getOwner();
		User destinationOwner = destination.getOwner();
		// Do not move trashed folders and contents.
		if (source.isDeleted())
			return;
		// Check permissions.
		if (!destination.hasWritePermission(user)
				|| !source.hasReadPermission(user)
				|| !source.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the " +
					"necessary permissions");
		// Use the same timestamp for all subsequent modifications to make
		// changes appear simultaneous.
		Date now = new Date();
		// If source and destination are not in the same user's namespace,
		// change owners and check quota.
		if (!sourceOwner.equals(destinationOwner)) {
			changeOwner(source, destinationOwner, user, now);
			if (getQuotaLeft(destinationOwner.getId()) < 0)
				throw new QuotaExceededException("Not enough free space " +
						"available in destination folder");
		}
		// Perform the move.
		Folder oldParent = source.getParent();
		oldParent.removeSubfolder(source);
		destination.addSubfolder(source);
		// Mark the former parent and destination trees upwards as modified.
		touchParentFolders(oldParent, user, now);
		touchParentFolders(source, user, now);
	}

	/**
	 * Recursively change the owner of the specified folder and all of its
	 * contents to the specified owner. Also mark them all as modified with the
	 * specified modifier and modificationDate.
	 */
	private void changeOwner(Folder folder, User owner, User modifier, Date modificationDate) {
		for (FileHeader file: folder.getFiles()) {
			file.setOwner(owner);
			file.getAuditInfo().setModificationDate(modificationDate);
			file.getAuditInfo().setModifiedBy(modifier);
		}
		for (Folder sub: folder.getSubfolders())
			changeOwner(sub, owner, modifier, modificationDate);
		folder.setOwner(owner);
		folder.getAuditInfo().setModificationDate(modificationDate);
		folder.getAuditInfo().setModifiedBy(modifier);
	}

	@Override
	public List<FileHeaderDTO> getDeletedFiles(Long userId) throws ObjectNotFoundException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");

		// Do the actual work.
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		List<FileHeader> files = fileDao.getDeletedFiles(userDao.get(userId));
		for (FileHeader f : files)
			result.add(f.getDTO());
		return result;
	}

	@Override
	public void removeFileFromTrash(Long userId, Long fileId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");

		// Do the actual work.
		FileHeader file = dao.getEntityById(FileHeader.class, fileId);
		Folder parent = file.getFolder();
		if (parent == null)
			throw new ObjectNotFoundException("The specified file has no parent folder");
		User user = dao.getEntityById(User.class, userId);
		if (!file.hasDeletePermission(user))
			throw new InsufficientPermissionsException("User " + user.getUsername() +
						" cannot restore file " + file.getName());

		file.setDeleted(false);
		dao.update(file);
		touchParentFolders(parent, user, new Date());
	}

	@Override
	public void moveFolderToTrash(Long userId, Long folderId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		Folder folder = dao.getEntityById(Folder.class, folderId);
		User user = dao.getEntityById(User.class, userId);
		if (!folder.hasDeletePermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		folder.setDeleted(true);
		dao.update(folder);
		touchParentFolders(folder, user, new Date());
		for (FileHeader file : folder.getFiles())
			moveFileToTrash(userId, file.getId());
		for (Folder subFolder : folder.getSubfolders())
			moveFolderToTrash(userId, subFolder.getId());

	}

	@Override
	public void removeFolderFromTrash(Long userId, Long folderId)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		Folder folder = dao.getEntityById(Folder.class, folderId);
		User user = dao.getEntityById(User.class, userId);
		if (!folder.hasDeletePermission(user))
			throw new InsufficientPermissionsException("User " + user.getUsername() +
						" cannot restore folder " + folder.getName());
		folder.setDeleted(false);
		for (FileHeader file : folder.getFiles())
			removeFileFromTrash(userId, file.getId());
		for (Folder subFolder : folder.getSubfolders())
			removeFolderFromTrash(userId, subFolder.getId());
		dao.update(folder);
		touchParentFolders(folder, user, new Date());
	}

	@Override
	public List<FolderDTO> getDeletedRootFolders(Long userId) throws ObjectNotFoundException {
		List<Folder> folders = folderDao.getDeletedRootFolders(userDao.get(userId));
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		for (Folder folder : folders)
			result.add(folder.getDTO());
		return result;
	}

	@Override
	public void emptyTrash(Long userId) throws ObjectNotFoundException, InsufficientPermissionsException {
		List<FolderDTO> deletedRootFolders = getDeletedRootFolders(userId);
		for (FolderDTO fdto : deletedRootFolders)
			deleteFolder(userId, fdto.getId());
		List<FileHeaderDTO> deletedFiles = getDeletedFiles(userId);
		for (FileHeaderDTO filedto : deletedFiles)
			deleteFile(userId, filedto.getId());
	}

	@Override
	public void restoreTrash(Long userId) throws ObjectNotFoundException, InsufficientPermissionsException {
		List<FolderDTO> deletedRootFolders = getDeletedRootFolders(userId);
		for (FolderDTO fdto : deletedRootFolders)
			removeFolderFromTrash(userId, fdto.getId());
		List<FileHeaderDTO> deletedFiles = getDeletedFiles(userId);
		for (FileHeaderDTO filedto : deletedFiles)
			removeFileFromTrash(userId, filedto.getId());
	}

	@Override
	public User createUser(String username, String name, String mail,
				String idp, String idpid) throws ObjectNotFoundException {
		if (username == null)
			throw new ObjectNotFoundException("No username specified");
		if (name == null)
			throw new ObjectNotFoundException("No name specified");

		User user = new User();
		user.setUsername(username);
		user.setName(name);
		user.setEmail(mail);
		user.setIdentityProvider(idp);
		user.setIdentityProviderId(idpid);
		Date now = new Date();
		AuditInfo auditInfo = new AuditInfo();
		auditInfo.setCreationDate(now);
		auditInfo.setModificationDate(now);
		user.setAuditInfo(auditInfo);
		user.setActive(true);
		user.generateAuthToken();
		user.generateWebDAVPassword();
		user.setUserClass(getDefaultUserClass());
		transaction.save(user);
		// Create the root folder for the user.
		createFolder(user.getName(), null, user);
		transaction.commit();
		return user;
	}

	/**
	 * Get the default user class, which is the one with the lowest quota.
	 */
	private UserClass getDefaultUserClass() {
		return getUserClasses().get(0);
	}

	@Override
	public List<UserClass> getUserClasses() {
		List<UserClass> classes = userClassDao.findUserClasses();
		// Create a default user class for first-time use. Afterwards, the
		// admin should modify or add to the userclass table.
		if (classes.size() == 0) {
			UserClass defaultClass = new UserClass();
			defaultClass.setName("default");
			Long defaultQuota = getConfiguration().getLong("quota", new Long(52428800L));
			defaultClass.setQuota(defaultQuota);
			transaction.save(defaultClass);
			classes.add(defaultClass);
			transaction.commit();
		}
		return classes;
	}

	@Override
	public User findUserByEmail(String email) {
		return dao.findUserByEmail(email);
	}

	@Override
	public void updateUser(User user) {
		transaction.save(user);
		transaction.commit();
	}

	@Override
	public User findUser(String username) {
		if (username == null)
			return null;
		return userDao.findUser(username);
	}

	@Override
	public User updateUserToken(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		user.generateAuthToken();
		return user;
	}

	@Override
	public Set<PermissionDTO> getFolderPermissions(Long userId, Long folderId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = userDao.get(userId);
		Folder folder = folderDao.get(folderId);
		if(!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		Set<Permission> perms = folder.getPermissions();
		Set<PermissionDTO> result = new LinkedHashSet<PermissionDTO>();
		for (Permission perm : perms)
			result.add(perm.getDTO());
		return result;

	}

	/**
	 * Set the provided permissions as the new permissions of the specified
	 * folder.
	 */
	private void setFolderPermissions(User user, Folder folder, Set<PermissionDTO> permissions)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (permissions != null && !permissions.isEmpty()) {
			User owner = folder.getOwner();
			PermissionDTO ownerPerm = null;
			for (PermissionDTO dto : permissions)
				if (dto.getUser() != null && dto.getUser().getId().equals(owner.getId())) {
					ownerPerm = dto;
					break;
				}
			if (ownerPerm == null || !ownerPerm.hasRead() || !ownerPerm.hasWrite() || !ownerPerm.hasModifyACL())
				throw new InsufficientPermissionsException("Can't remove permissions from owner");
			// Delete previous entries
			folder.getPermissions().clear();
			for (PermissionDTO dto : permissions) {
				// Skip 'empty' permission entries.
				if (!dto.getRead() && !dto.getWrite() && !dto.getModifyACL())
					continue;
				folder.addPermission(getPermission(dto));
			}
			transaction.save(folder);
			for (FileHeader file : folder.getFiles()) {
				setFilePermissions(file, permissions);
				Date now = new Date();
				file.getAuditInfo().setModificationDate(now);
				file.getAuditInfo().setModifiedBy(user);
				transaction.save(file);
			}
			for (Folder sub : folder.getSubfolders())
				setFolderPermissions(user, sub, permissions);
		}
	}

	private Permission getPermission(PermissionDTO dto) throws ObjectNotFoundException {
		Permission res = new Permission();
		if (dto.getGroup() != null) {
			Group group = groupDao.get(dto.getGroup().getId());
			if (group == null)
				throw new ObjectNotFoundException("Group " +
						dto.getGroup().getId() + "not found");
			res.setGroup(group);
		} else if (dto.getUser() != null)
			if (dto.getUser().getId() == null) {
				User user = userDao.findUser(dto.getUser().getUsername());
				if (user == null)
					throw new ObjectNotFoundException("User " +
							dto.getUser().getUsername() + "not found");
				res.setUser(user);
			} else {
				User user = userDao.get(dto.getUser().getId());
				if (user == null)
					throw new ObjectNotFoundException("User " +
							dto.getUser().getId() + "not found");
				res.setUser(user);
			}
		res.setRead(dto.hasRead());
		res.setWrite(dto.hasWrite());
		res.setModifyACL(dto.hasModifyACL());
		return res;
	}

	@Override
	public List<UserDTO> getUsersByUserNameLike(String username) {
		List<User> users = userDao.getUsersByUserName(username);
		List<UserDTO> result = new ArrayList<UserDTO>();
		for (User u : users)
			result.add(u.getDTO());
		return result;

	}

	@Override
	public void addMemberToGroup(Long userId, Long groupId, Long newMemberId) throws ObjectNotFoundException, DuplicateNameException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (groupId == null)
			throw new ObjectNotFoundException("No group specified");
		if (newMemberId == null)
			throw new ObjectNotFoundException("No user to add specified");
		User user = userDao.get(userId);
		Group group = groupDao.get(groupId);
		if (!group.getOwner().equals(user))
			throw new InsufficientPermissionsException();
		User newMember = userDao.get(newMemberId);
		if (group.contains(newMember))
			throw new DuplicateNameException("User already exists in group");
		group.addMember(newMember);
		transaction.save(group);
		if (user.equals(newMember))
			user.addToGroup(group);
		else
			newMember.addToGroup(group);
		user.updateGroup(group);
		transaction.save(user);
		if (!user.equals(newMember))
			transaction.save(newMember);
		transaction.commit();
	}

	@Override
	public void invalidateUserToken(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = dao.getEntityById(User.class, userId);
		user.invalidateAuthToken();
		return;
	}

	@Override
	public List<FolderDTO> getSharedRootFolders(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		List<Folder> folders = folderDao.getSharedRootFolders(userDao.get(userId));
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		for (Folder f : folders) {
			FolderDTO dto = f.getDTO();
			dto.setSubfolders(getSharedSubfolders(userId, f.getId()));
			result.add(dto);
		}
		return result;
	}

	@Override
	public void removeMemberFromGroup(Long userId, Long groupId, Long memberId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (groupId == null)
			throw new ObjectNotFoundException("No group specified");
		if (memberId == null)
			throw new ObjectNotFoundException("No member specified");
		User owner = userDao.get(userId);
		Group group = groupDao.get(groupId);
		User member = userDao.get(memberId);
		if (!group.getOwner().equals(owner))
			throw new InsufficientPermissionsException("User is not the owner of the group");
		group.removeMember(member);
		transaction.save(group);
		if (owner.equals(member))
			owner.removeFromGroup(group);
		else
			member.removeFromGroup(group);
		owner.updateGroup(group);
		transaction.save(owner);
		if (!owner.equals(member))
			transaction.save(member);
		transaction.commit();
	}

	@Override
	public List<UserDTO> getUsersSharingFoldersForUser(Long userId) {
		User user = userDao.get(userId);
		List<User> users = userDao.getUsersSharingFoldersForUser(user);
		List<User> usersFiles = userDao.getUsersSharingFilesForUser(user);
		List<UserDTO> res = new ArrayList<UserDTO>();
		for (User u : users)
			res.add(u.getDTO());
		for (User fu : usersFiles)
			if (!users.contains(fu))
				res.add(fu.getDTO());
		return res;
	}

	@Override
	public Set<PermissionDTO> getFilePermissions(Long userId, Long fileId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = userDao.get(userId);
		FileHeader folder = fileDao.get(fileId);
		if(!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		Set<Permission> perms = folder.getPermissions();
		Set<PermissionDTO> result = new LinkedHashSet<PermissionDTO>();
		for (Permission perm : perms)
			result.add(perm.getDTO());
		return result;
	}

	/**
	 * Set the provided permissions as the new permissions of the specified
	 * file. This method sets the modification date/user attributes to the
	 * current values as a side effect.
	 */
	private void setFilePermissions(FileHeader file,
				Set<PermissionDTO> permissions)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (permissions != null && !permissions.isEmpty()) {
			PermissionDTO ownerPerm = null;
			for (PermissionDTO dto : permissions)
				if (dto.getUser() != null && dto.getUser().getId().equals(file.getOwner().getId())) {
					ownerPerm = dto;
					break;
				}
			if (ownerPerm == null || !ownerPerm.hasRead() || !ownerPerm.hasWrite() || !ownerPerm.hasModifyACL())
				throw new InsufficientPermissionsException("Can't remove permissions from owner");
			// Delete previous entries.
			file.getPermissions().clear();
			for (PermissionDTO dto : permissions) {
				// Skip 'empty' permission entries.
				if (!dto.getRead() && !dto.getWrite() && !dto.getModifyACL()) continue;
				file.addPermission(getPermission(dto));
			}
			transaction.save(file);
		}
	}

	@Override
	public List<FileHeaderDTO> getSharedFilesNotInSharedFolders(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		List<FileHeader> files = fileDao.getSharedFilesNotInSharedFolders(userDao.get(userId));
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		for (FileHeader f : files)
			result.add(f.getDTO());
		return result;
	}

	@Override
	public List<FileHeaderDTO> getSharedFiles(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		List<FileHeader> files = dao.getSharedFiles(userId);
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		for (FileHeader f : files)
			result.add(f.getDTO());
		return result;
	}

	@Override
	public List<FolderDTO> getSharedFolders(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		List<Folder> folders = dao.getSharedFolders(userId);
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		for (Folder f : folders)
			result.add(f.getDTO());
		return result;
	}

	@Override
	public List<FileHeaderDTO> getSharedFiles(Long ownerId, Long callingUserId) throws ObjectNotFoundException {
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (callingUserId == null)
			throw new ObjectNotFoundException("No calling user specified");
		List<FileHeader> folders = dao.getSharedFiles(ownerId, callingUserId);
		List<FileHeaderDTO> result = new ArrayList<FileHeaderDTO>();
		for (FileHeader f : folders)
			result.add(f.getDTO());
		return result;
	}

	@Override
	public List<FolderDTO> getSharedRootFolders(Long ownerId, Long callingUserId) throws ObjectNotFoundException {
		if (ownerId == null)
			throw new ObjectNotFoundException("No owner specified");
		if (callingUserId == null)
			throw new ObjectNotFoundException("No calling user specified");
		List<Folder> folders = dao.getSharedRootFolders(ownerId, callingUserId);
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		for (Folder f : folders) {
			FolderDTO dto = f.getDTO();
			dto.setSubfolders(getSharedSubfolders(ownerId, callingUserId, f.getId()));
			result.add(dto);
		}
		return result;

	}

	@Override
	public List<FolderDTO> getSharedSubfolders(Long userId, Long folderId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = dao.getEntityById(User.class, userId);
		Folder folder = dao.getEntityById(Folder.class, folderId);
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		if (folder.isShared(user))
			for (Folder f : folder.getSubfolders())
				if (f.isShared(user) && !f.isDeleted())
					result.add(f.getDTO());
		return result;
	}

	@Override
	public List<FolderDTO> getSharedSubfolders(Long userId, Long callingUserId, Long folderId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (callingUserId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = dao.getEntityById(User.class, callingUserId);
		Folder folder = dao.getEntityById(Folder.class, folderId);
		List<FolderDTO> result = new ArrayList<FolderDTO>();
		if (folder.isSharedForOtherUser(user))
			for (Folder f : folder.getSubfolders())
				if (f.isSharedForOtherUser(user) && !f.isDeleted()){
					FolderDTO dto = f.getDTO();
					dto.setSubfolders(getSharedSubfolders(userId, callingUserId, dto.getId()));
					result.add(dto);
				}
		return result;

	}

	@Override
	public List<FileHeaderDTO> searchFiles(Long userId, String query) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = getUser(userId);
		if (query == null)
			throw new ObjectNotFoundException("No query specified");
		List<FileHeader> files = search(user.getId(), query);
		List<FileHeaderDTO> res = new ArrayList<FileHeaderDTO>();
		for(FileHeader f : files)
			res.add(f.getDTO());
		return res;
	}

	/**
	 * Performs the actuals search on the solr server and returns the results
	 *
	 * We have to use the dismax query type (instead of the
	 * standard) because it allows for search time field boosting. This is because we can't use indexing
	 * time field boosting due to the patched rich indexing API that does not allow it
	 *
	 * @param userId
	 * @param query
	 * @return a List of FileHeader objects
	 */
	private List<FileHeader> search(Long userId, String query) {
		return new ArrayList<FileHeader>();/*
		try {
			HttpClient httpClient = new HttpClient();

			GetMethod method = new GetMethod(getConfiguration().getString("solrSelectUrl"));
			NameValuePair[] params = {new NameValuePair("qt", "dismax"),
										new NameValuePair("q", query),
										new NameValuePair("sort", "score desc"),
										new NameValuePair("indent", "on")};
			method.setQueryString(params);
			int retryCount = 0;
			int statusCode = 0;
			String response = null;
			do {
				statusCode = httpClient.executeMethod(method);
				logger.debug("HTTP status: " + statusCode);
				response = method.getResponseBodyAsString();
				logger.debug(response);
				retryCount++;
				if (statusCode != 200 && retryCount < 3)
					try {
						Thread.sleep(3000); //Give Solr a little time to be available
					} catch (InterruptedException e) {
					}
			} while (statusCode != 200 && retryCount < 3);
			if (statusCode != 200)
				throw new EJBException("Search query return error:\n" + response);

			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(method.getResponseBodyAsStream());
			method.releaseConnection();

			Node root = doc.getElementsByTagName("response").item(0);
			Node lst = root.getFirstChild().getNextSibling();
			Node status = lst.getFirstChild().getNextSibling();
			if (status.getAttributes().getNamedItem("name").getNodeValue().equals("status") &&
				status.getTextContent().equals("0")) {
				List<FileHeader> fileResult = new ArrayList<FileHeader>();
				Node result = lst.getNextSibling().getNextSibling();
				NodeList docs = result.getChildNodes();
				User user = getUser(userId);
				for (int i=1; i<docs.getLength(); i=i+2) {
					Node d = docs.item(i);
					NodeList docData = d.getChildNodes();
					for (int j=1; j<docData.getLength(); j=j+2) {
						Node dd = docData.item(j);
						if (dd.getAttributes().item(0).getNodeName().equals("name") &&
							dd.getAttributes().item(0).getNodeValue().equals("id")) {
							Long fileId = Long.valueOf(dd.getTextContent());
							try {
								FileHeader file = dao.getEntityById(FileHeader.class, fileId);
								if (file.hasReadPermission(user)) {
									fileResult.add(file);
									logger.debug("File added " + fileId);
								}
							} catch (ObjectNotFoundException e) {
								logger.warn("Search result not found", e);
							}
						}
					}
				}
				return fileResult;
			}
			throw new EJBException();
		} catch (HttpException e) {
			throw new EJBException(e);
		} catch (IOException e) {
			throw new EJBException(e);
		} catch (SAXException e) {
			throw new EJBException(e);
		} catch (ParserConfigurationException e) {
			throw new EJBException(e);
		} catch (ObjectNotFoundException e) {
			throw new EJBException(e);
		}*/
	}

	@Override
	public void copyFiles(Long userId, List<Long> fileIds, Long destId) throws ObjectNotFoundException, DuplicateNameException, GSSIOException, InsufficientPermissionsException, QuotaExceededException {
		for(Long l : fileIds){
			FileHeader file = dao.getEntityById(FileHeader.class, l);
			copyFile(userId, l, destId, file.getName());
		}


	}

	@Override
	public void moveFiles(Long userId, List<Long> fileIds, Long destId) throws InsufficientPermissionsException, ObjectNotFoundException, QuotaExceededException {
		for(Long l : fileIds){
			FileHeader file = dao.getEntityById(FileHeader.class, l);
			moveFile(userId, l, destId, file.getName());
		}

	}

	@Override
	public void deleteFiles(Long userId, List<Long> fileIds)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		List<String> filesToRemove = new ArrayList<String>();
		// First delete database objects.
		for (Long fileId : fileIds){
			if (fileId == null)
				throw new ObjectNotFoundException("No file specified");
			FileHeader file = fileDao.get(fileId);
			Folder parent = file.getFolder();
			if (parent == null)
				throw new ObjectNotFoundException("The specified file has no parent folder");
			if (!file.hasDeletePermission(user))
				throw new InsufficientPermissionsException("User " +
							user.getId() + " cannot delete file " +
							file.getName() + "(" + file.getId() + ")");

			parent.removeFile(file);
			for (FileBody body : file.getBodies())
				filesToRemove.add(body.getStoredPath());
			fileDao.delete(file);
			touchParentFolders(parent, user, new Date());
		}
		// Then remove physical files if everything is ok.
		for (String physicalFileName : filesToRemove)
			deleteActualFile(physicalFileName);
		// Then remove deleted files from the index.
		for (Long fileId : fileIds)
			indexFile(fileId, true);
	}

	@Override
	public void moveFilesToTrash(Long userId, List<Long> fileIds) throws ObjectNotFoundException, InsufficientPermissionsException {
		for(Long l : fileIds)
			moveFileToTrash(userId, l);

	}

	@Override
	public void removeFilesFromTrash(Long userId, List<Long> fileIds) throws ObjectNotFoundException, InsufficientPermissionsException {
		for(Long l : fileIds)
			removeFileFromTrash(userId, l);

	}

	@Override
	public Nonce createNonce(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = dao.getEntityById(User.class, userId);
		Nonce nonce = Nonce.createNonce(user.getId());
		dao.create(nonce);
		return nonce;
	}

	@Override
	public Nonce getNonce(String nonce, Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (nonce == null)
			throw new ObjectNotFoundException("No nonce specified");
		return dao.getNonce(nonce, userId);
	}

	@Override
	public void removeNonce(Long id) throws ObjectNotFoundException {
		if (id == null)
			throw new ObjectNotFoundException("No nonce specified");
		Nonce nonce = dao.getEntityById(Nonce.class, id);
		dao.delete(nonce);
	}

	@Override
	public void activateUserNonce(Long userId, String nonce, Date nonceExpiryDate) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = dao.getEntityById(User.class, userId);
		user.setNonce(nonce);
		user.setNonceExpiryDate(nonceExpiryDate);
	}

	@Override
	public StatsDTO getUserStatistics(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		StatsDTO stats = new StatsDTO();
		stats.setFileCount(fileDao.getFileCount(userDao.get(userId)));
		Long fileSize = fileDao.getFileSize(userId);
		stats.setFileSize(fileSize);
		Long quota = getQuota(userId);
		Long quotaLeft = quota - fileSize;
		stats.setQuotaLeftSize(quotaLeft);
		return stats;
	}

	@Override
	public List<FileBodyDTO> getVersions(Long userId, Long fileId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		User user = dao.getEntityById(User.class, userId);
		FileHeader header = dao.getEntityById(FileHeader.class, fileId);
		if(!header.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		List<FileBodyDTO> result = new LinkedList<FileBodyDTO>();
		for(int i = header.getBodies().size()-1 ; i>=0; i--)
			result.add(header.getBodies().get(i).getDTO());
		return result;
	}

	@Override
	public void removeVersion(Long userId, Long fileId, Long bodyId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (bodyId == null)
			throw new ObjectNotFoundException("No body specified");
		User user = dao.getEntityById(User.class, userId);
		FileHeader header = dao.getEntityById(FileHeader.class, fileId);
		if(!header.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		FileBody body = dao.getEntityById(FileBody.class, bodyId);
		if(body.equals(header.getCurrentBody())){

			if(header.getBodies().size() == 1)
				throw new InsufficientPermissionsException("You cant delete this version, Delete file instead!");
			for(FileBody b : header.getBodies())
				if(b.getVersion() == body.getVersion()-1)
					header.setCurrentBody(b);
		}
		deleteActualFile(body.getStoredPath());
		header.getBodies().remove(body);

		Folder parent = header.getFolder();
		touchParentFolders(parent, user, new Date());

	}

	@Override
	public void restoreVersion(Long userId, Long fileId, int version) throws ObjectNotFoundException, InsufficientPermissionsException,  GSSIOException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		User user = userDao.get(userId);
		FileHeader header = fileDao.get(fileId);
		if(!header.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		FileBody body = fileDao.getFileVersion(fileId, version);
		File fileContents = new File(body.getStoredPath());

		try {
			updateFileContents(userId, fileId, body.getMimeType(), new FileInputStream(fileContents) );
		} catch (FileNotFoundException e) {
			throw new GSSIOException(e);
		}
	}

	@Override
	public FileHeader removeOldVersions(User user, FileHeader file)
			throws InsufficientPermissionsException {
		if (!file.hasWritePermission(user))
			throw new InsufficientPermissionsException("You don't have the " +
					"necessary permissions");
		Iterator<FileBody> it = file.getBodies().iterator();
		while (it.hasNext()){
			FileBody body = it.next();
			if (!body.getId().equals(file.getCurrentBody().getId())){
				deleteActualFile(body.getStoredPath());
				it.remove();
			}
		}
		file.getCurrentBody().setVersion(1);
		file.getBodies().get(0).setVersion(1);

		Folder parent = file.getFolder();
		touchParentFolders(parent, user, new Date());
		return file;
	}

	/**
	 * Gets the quota left for specified user ID.
	 */
	private Long getQuotaLeft(Long userId) throws ObjectNotFoundException{
		Long fileSize = fileDao.getFileSize(userId);
		Long quota = getQuota(userId);
		return quota - fileSize;
	}

	/**
	 * Gets the quota for specified user ID.
	 */
	private Long getQuota(Long userId) throws ObjectNotFoundException{
		UserClass uc = getUser(userId).getUserClass();
		if (uc == null)
			uc = getDefaultUserClass();
		return uc.getQuota();
	}

	@Override
	public void rebuildSolrIndex() {
		return;/*
		MessageProducer sender = null;
		Session session = null;
		Connection qConn = null;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.newDocument();
			Node root = doc.createElement("delete");
			doc.appendChild(root);
			Node queryNode = doc.createElement("query");
			root.appendChild(queryNode);
			queryNode.appendChild(doc.createTextNode("*:*"));

			TransformerFactory fact = TransformerFactory.newInstance();
			Transformer trans = fact.newTransformer();
			trans.setOutputProperty(OutputKeys.INDENT, "yes");
			StringWriter sw = new StringWriter();
			StreamResult sr = new StreamResult(sw);
			DOMSource source = new DOMSource(doc);
			trans.transform(source, sr);
			logger.debug(sw.toString());

			HttpClient httpClient = new HttpClient();
			PostMethod method = new PostMethod(getConfiguration().getString("solrUpdateUrl"));
			method.setRequestEntity(new StringRequestEntity(sw.toString()));
			int retryCount = 0;
			int statusCode = 0;
			String response = null;
			do {
				statusCode = httpClient.executeMethod(method);
				logger.debug("HTTP status: " + statusCode);
				response = method.getResponseBodyAsString();
				logger.debug(response);
				retryCount++;
				if (statusCode != 200 && retryCount < 3)
					try {
						Thread.sleep(10000); //Give Solr a little time to be available
					} catch (InterruptedException e) {
					}
			} while (statusCode != 200 && retryCount < 3);
			method.releaseConnection();
			if (statusCode != 200)
				throw new EJBException("Cannot clear Solr index. Solr response is:\n" + response);
			List<Long> fileIds = dao.getAllFileIds();

			Context jndiCtx = new InitialContext();
			ConnectionFactory factory = (QueueConnectionFactory) jndiCtx.lookup("java:/JmsXA");
			Queue queue = (Queue) jndiCtx.lookup("queue/gss-indexingQueue");
			qConn = factory.createConnection();
			session = qConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
			sender = session.createProducer(queue);

			for (Long id : fileIds) {
				MapMessage map = session.createMapMessage();
				map.setObject("id", id);
				map.setBoolean("delete", false);
				sender.send(map);
			}
			sendOptimize(httpClient, 0);
		} catch (DOMException e) {
			throw new EJBException(e);
		} catch (TransformerConfigurationException e) {
			throw new EJBException(e);
		} catch (IllegalArgumentException e) {
			throw new EJBException(e);
		} catch (HttpException e) {
			throw new EJBException(e);
		} catch (UnsupportedEncodingException e) {
			throw new EJBException(e);
		} catch (ParserConfigurationException e) {
			throw new EJBException(e);
		} catch (TransformerException e) {
			throw new EJBException(e);
		} catch (IOException e) {
			throw new EJBException(e);
		} catch (NamingException e) {
			throw new EJBException(e);
		} catch (JMSException e) {
			throw new EJBException(e);
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
		}*/
	}

	/**
	 * Sends a optimize message to the solr server
	 *
	 * @param httpClient
	 * @param retryCount If the commit fails, it is retried three times. This parameter is passed in the recursive
	 * 					calls to stop the recursion
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 * @throws HttpException
	 */
	private void sendOptimize(HttpClient httpClient, int retryCount) throws UnsupportedEncodingException, IOException, HttpException {
		PostMethod method = null;
		try {
			logger.debug("Optimize retry: " + retryCount);
			method = new PostMethod(getConfiguration().getString("solrUpdateUrl"));
			method.setRequestEntity(new StringRequestEntity("<optimize/>", "text/xml", "iso8859-1"));
			int statusCode = httpClient.executeMethod(method);
			logger.debug("HTTP status: " + statusCode);
			String response = method.getResponseBodyAsString();
			logger.debug(response);
			if (statusCode != 200 && retryCount < 2) {
				try {
					Thread.sleep(10000); //Give Solr a little time to be available
				} catch (InterruptedException e) {
				}
				sendOptimize(httpClient, retryCount + 1);
			}
		}
		finally {
			if (method != null)
				method.releaseConnection();
		}
	}

	@Override
	public FileHeader createFile(Long userId, Long folderId, String name, String mimeType, long fileSize, String filePath)
			throws DuplicateNameException, ObjectNotFoundException, GSSIOException,
			InsufficientPermissionsException, QuotaExceededException {
		// Validate.
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		String contentType = mimeType;
		if (StringUtils.isEmpty(mimeType))
			contentType = DEFAULT_MIME_TYPE;
		if (StringUtils.isEmpty(name))
			throw new ObjectNotFoundException("No file name specified");
		if (folderDao.exists(folderId, name))
			throw new DuplicateNameException("A folder with the name '" + name +
						"' already exists at this level");
		if (fileDao.exists(folderId, name))
			throw new DuplicateNameException("A file with the name '" + name +
						"' already exists at this level");

		// Do the actual work.
		Folder parent = folderDao.get(folderId);
		if (parent == null)
			throw new ObjectNotFoundException("Parent folder not found");

		User owner = userDao.get(userId);
		if (!parent.hasWritePermission(owner))
			throw new InsufficientPermissionsException("You don't have the permissions to write to this folder");
		FileHeader file = new FileHeader();
		file.setName(name);
		parent.addFile(file);
		// set file owner to folder owner
		file.setOwner(parent.getOwner());

		Date now = new Date();
		AuditInfo auditInfo = new AuditInfo();
		auditInfo.setCreatedBy(owner);
		auditInfo.setCreationDate(now);
		auditInfo.setModifiedBy(owner);
		auditInfo.setModificationDate(now);
		file.setAuditInfo(auditInfo);
		// TODO set the proper versioning flag on creation
		file.setVersioned(false);

		for (Permission p : parent.getPermissions()) {
			Permission permission = new Permission();
			permission.setGroup(p.getGroup());
			permission.setUser(p.getUser());
			permission.setRead(p.getRead());
			permission.setWrite(p.getWrite());
			permission.setModifyACL(p.getModifyACL());
			file.addPermission(permission);
		}

		// Create the file body.
		try {
			createFileBody(name, contentType, fileSize, filePath, file, auditInfo);
		} catch (FileNotFoundException e) {
			throw new GSSIOException(e);
		}
		touchParentFolders(parent, owner, now);
		transaction.save(file);
		transaction.commit();
		indexFile(file.getId(), false);

		return file;
	}

	@Override
	public FileHeaderDTO updateFileContents(Long userId, Long fileId,
			String mimeType, long fileSize, String filePath)
			throws ObjectNotFoundException, GSSIOException,
			InsufficientPermissionsException, QuotaExceededException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");

		FileHeader file = fileDao.get(fileId);
		String contentType = getContentType(file.getName(), mimeType);

		User owner = userDao.get(userId);
		if (!file.hasWritePermission(owner))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		Date now = new Date();
		AuditInfo auditInfo = new AuditInfo();
		auditInfo.setCreatedBy(owner);
		auditInfo.setCreationDate(now);
		auditInfo.setModifiedBy(owner);
		auditInfo.setModificationDate(now);
		try {
			createFileBody(file.getName(), contentType, fileSize, filePath, file, auditInfo);
		} catch (FileNotFoundException e) {
			throw new GSSIOException(e);
		}
		Folder parent = file.getFolder();
		touchParentFolders(parent, owner, new Date());
		transaction.commit();

		indexFile(fileId, false);
		return file.getDTO();
	}

	/**
	 * A helper method that returns the proper Content-Type for the specified
	 * file name. If the specified mimeType is empty or overly generic, then
	 * it tries to identify it from the filename extension.
	 *
	 * @param filename the name of the file
	 * @param mimeType the client-provided MIME type
	 * @return the most accurate Content-Type
	 */
	private String getContentType(String filename, String mimeType) {
		String contentType = mimeType;
		if (StringUtils.isEmpty(mimeType) || "application/octet-stream".equals(mimeType)
					|| "application/download".equals(mimeType) || "application/force-download".equals(mimeType)
					|| "octet/stream".equals(mimeType) || "application/unknown".equals(mimeType)) {
			if (filename.indexOf('.') != -1) {
				String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ENGLISH);
				if (".doc".equals(extension))
					contentType =  "application/msword";
				else if (".xls".equals(extension))
					contentType =  "application/vnd.ms-excel";
				else if (".ppt".equals(extension))
					contentType =  "application/vnd.ms-powerpoint";
				else if (".pdf".equals(extension))
					contentType =  "application/pdf";
				else if (".gif".equals(extension))
					contentType =  "image/gif";
				else if (".jpg".equals(extension) || ".jpeg".equals(extension) || ".jpe".equals(extension))
					contentType =  "image/jpeg";
				else if (".tiff".equals(extension) || ".tif".equals(extension))
					contentType =  "image/tiff";
				else if (".png".equals(extension))
					contentType =  "image/png";
				else if (".bmp".equals(extension))
					contentType =  "image/bmp";
			}
			// When everything else fails, assign the default MIME type.
			contentType =  DEFAULT_MIME_TYPE;
		}
		return contentType;
	}

	/**
	 * Helper method to create a new file body and attach it as the current body
	 * of the provided file header.
	 *
	 * @param name the original file name
	 * @param mimeType the content type
	 * @param fileSize the uploaded file size
	 * @param filePath the uploaded file full path
	 * @param header the file header that will be associated with the new body
	 * @param auditInfo the audit info
	 * @param owner the owner of the file
	 * @throws FileNotFoundException
	 * @throws QuotaExceededException
	 * @throws ObjectNotFoundException if the owner was not found
	 */
	private void createFileBody(String name, String mimeType, long fileSize, String filePath,
				FileHeader header, AuditInfo auditInfo)
			throws FileNotFoundException, QuotaExceededException, ObjectNotFoundException {

		long currentTotalSize = 0;
		if (!header.isVersioned() && header.getCurrentBody() != null && header.getBodies() != null)
			currentTotalSize = header.getTotalSize();
		Long quotaLeft = getQuotaLeft(header.getOwner().getId());
		if (quotaLeft < fileSize-currentTotalSize) {
			// Delete the file if the quota was exceeded.
			deleteActualFile(filePath);
			throw new QuotaExceededException("Not enough free space available");
		}

		FileBody body = new FileBody();
		String contentType = getContentType(name, mimeType);
		body.setMimeType(contentType);
		body.setAuditInfo(auditInfo);
		body.setSize(fileSize);
		body.setOriginalName(name);
		body.setStoredPath(filePath);
		// Clear the old version if the file is not versioned and gets updated.
		if (!header.isVersioned() && header.getCurrentBody() != null) {
			header.setCurrentBody(null);
			if (header.getBodies() != null) {
				Iterator<FileBody> it = header.getBodies().iterator();
				while (it.hasNext()){
					FileBody bo = it.next();
					deleteActualFile(bo.getStoredPath());
					it.remove();
				}
			}
		}

		header.addBody(body);
		header.setAuditInfo(auditInfo);

		transaction.save(header);
	}


	@Override
	public File uploadFile(InputStream stream, Long userId) throws IOException, ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User owner = userDao.get(userId);
		if(owner == null)
			throw new ObjectNotFoundException("No user specified");
		long start = 0, end = 0;
		if (logger.isDebugEnabled())
			start = System.currentTimeMillis();
		File result = new File(generateRepositoryFilePath());
		try {
			final FileOutputStream output = new FileOutputStream(result);
			final byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
			int n = 0;

			while (-1 != (n = stream.read(buffer)))
				output.write(buffer, 0, n);
			output.close();
			stream.close();
		} catch (IOException e) {
			if (!result.delete())
				logger.warn("Could not delete " + result.getPath());
			throw e;
		}
		if (logger.isDebugEnabled()) {
			end = System.currentTimeMillis();
			logger.debug("Time to upload: " + (end - start) + " (msec)");
		}
		return result;
	}


	@Override
	public void createFileUploadProgress(Long userId, String filename, Long bytesTransfered, Long fileSize) throws ObjectNotFoundException{
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		FileUploadStatus status = fileUploadDao.getStatus(user, filename);
		if (status == null) {
			status = new FileUploadStatus();
			status.setOwner(user);
			status.setFilename(filename);
			status.setBytesUploaded(bytesTransfered);
			status.setFileSize(fileSize);
			transaction.save(status);
		} else {
			status.setBytesUploaded(bytesTransfered);
			status.setFileSize(fileSize);
			transaction.save(status);
		}
		transaction.commit();
	}

	@Override
	public void removeFileUploadProgress(Long userId, String filename) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		FileUploadStatus status = fileUploadDao.getStatus(userDao.get(userId), filename);
		if (status != null)
			fileUploadDao.delete(status);
	}

	@Override
	public FileUploadStatus getFileUploadStatus(Long userId, String fileName) {
		return fileUploadDao.getStatus(userDao.get(userId), fileName);
	}

	@Override
	public FolderDTO getFolderWithSubfolders(Long userId, Long folderId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		final User user = dao.getEntityById(User.class, userId);
		final Folder folder = dao.getEntityById(Folder.class, folderId);
		// Check permissions
		if (!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the permissions to read this folder");
		List<FolderDTO> subfolders = new ArrayList<FolderDTO>();
		if (folder.hasReadPermission(user))
			for (Folder f : folder.getSubfolders())
				if (f.hasReadPermission(user) && !f.isDeleted())
					subfolders.add(f.getDTO());
		FolderDTO result = folder.getDTO();
		result.setSubfolders(subfolders);
		return folder.getDTO();
	}

	@Override
	public FolderDTO getFolderWithSubfolders(Long userId, Long callingUserId, Long folderId) throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = dao.getEntityById(User.class, callingUserId);
		Folder folder = dao.getEntityById(Folder.class, folderId);
		// Check permissions
		if (!folder.hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the permissions to read this folder");

		FolderDTO result = folder.getDTO();
		result.setSubfolders(getSharedSubfolders(userId, callingUserId, folder.getId()));
		return result;
	}

	@Override
	public FileBodyDTO getFileVersion(Long userId, Long fileId, int version)
			throws ObjectNotFoundException, InsufficientPermissionsException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (fileId == null)
			throw new ObjectNotFoundException("No file specified");
		if (version < 1)
			throw new ObjectNotFoundException("No valid version specified");
		User user = userDao.get(userId);
		FileHeader file = fileDao.get(fileId);
		if (!file.hasReadPermission(user) && !file.getFolder().hasReadPermission(user))
			throw new InsufficientPermissionsException("You don't have the necessary permissions");
		FileBody body = fileDao.getFileVersion(fileId, version);
		return body.getDTO();
	}

	@Override
	public User updateUserPolicyAcceptance(Long userId, boolean isAccepted) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		user.setAcceptedPolicy(isAccepted);
		transaction.save(user);
		transaction.commit();
		return user;
	}

	@Override
	public void updateAccounting(User user, Date date, long bandwidthDiff) {
		accountingDao.updateAccounting(user, date, bandwidthDiff);
	}

	@Override
	public boolean canReadFolder(Long userId, Long folderId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		if (folderId == null)
			throw new ObjectNotFoundException("No folder specified");
		User user = userDao.get(userId);
		Folder folder = folderDao.get(folderId);
		// Check permissions
		if (!folder.hasReadPermission(user))
			return false;
		return true;
	}

	@Override
	public String resetWebDAVPassword(Long userId) throws ObjectNotFoundException {
		if (userId == null)
			throw new ObjectNotFoundException("No user specified");
		User user = userDao.get(userId);
		user.generateWebDAVPassword();
		return user.getWebDAVPassword();
	}

	@Override
	public Invitation findInvite(String code) {
		if (code == null)
			return null;
		return dao.findInvite(code);
	}

	@Override
	public void createLdapUser(String username, String firstname, String lastname, String email, String password) {
		LDAPConnection lc = new LDAPConnection();
        LDAPAttributeSet attributeSet = new LDAPAttributeSet();
        attributeSet.add(new LDAPAttribute("objectClass", getConfiguration().getStringArray("objectClass")));
        attributeSet.add(new LDAPAttribute("uid", username));
        attributeSet.add(new LDAPAttribute("cn", new String[]{firstname + " " + lastname}));
        attributeSet.add(new LDAPAttribute("sn", lastname));
        attributeSet.add(new LDAPAttribute("givenName", firstname));
        attributeSet.add(new LDAPAttribute("mail", email));
        attributeSet.add(new LDAPAttribute("userPassword", password));
        String dn = "uid=" + username + "," + getConfiguration().getString("baseDn");
        LDAPEntry newEntry = new LDAPEntry(dn, attributeSet);
        try {
        	lc.connect(getConfiguration().getString("ldapHost"), LDAPConnection.DEFAULT_PORT);
        	lc.bind(LDAPConnection.LDAP_V3, getConfiguration().getString("bindDn"),
        			getConfiguration().getString("bindPassword").getBytes("UTF8"));
        	lc.add(newEntry);
        	logger.info("Successfully added LDAP account: " + dn);
        	lc.disconnect();
        } catch(LDAPException e) {
        	throw new RuntimeException(e);
        } catch(UnsupportedEncodingException e) {
        	throw new RuntimeException(e);
        }

	}

	@Override
	public UserClass upgradeUserClass(String username, String code) throws ObjectNotFoundException, InvitationUsedException {
		User user = findUser(username);
		if (user == null)
			throw new ObjectNotFoundException("The user was not found");
		Invitation invite = findInvite(code);
		if (invite.getUser() != null)
			throw new InvitationUsedException("This code has already been used");
		invite.setUser(user);
		UserClass couponClass = getCouponUserClass();
		user.setUserClass(couponClass);
		return couponClass;
	}

	@Override
	public UserClass getCouponUserClass() {
		return dao.findCouponUserClass();
	}

}
