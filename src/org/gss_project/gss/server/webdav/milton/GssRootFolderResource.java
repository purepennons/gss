/*
 * Copyright 2011 Electronic Business Systems Ltd.
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
package org.gss_project.gss.server.webdav.milton;

import org.gss_project.gss.common.exceptions.InsufficientPermissionsException;
import org.gss_project.gss.common.exceptions.ObjectNotFoundException;
import org.gss_project.gss.common.exceptions.RpcException;
import org.gss_project.gss.server.domain.FileHeader;
import org.gss_project.gss.server.domain.Folder;
import org.gss_project.gss.server.domain.User;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bradmcevoy.http.Auth;
import com.bradmcevoy.http.LockInfo;
import com.bradmcevoy.http.LockResult;
import com.bradmcevoy.http.LockTimeout;
import com.bradmcevoy.http.LockToken;
import com.bradmcevoy.http.Request;
import com.bradmcevoy.http.Resource;
import com.bradmcevoy.http.Request.Method;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.http11.auth.DigestResponse;


/**
 * @author kman
 *
 */
public class GssRootFolderResource extends GssFolderResource{
	private static final Logger log = LoggerFactory.getLogger(GssFolderResource.class);
	String path;
	/**
	 * @param host
	 * @param factory
	 * @param resource
	 */
	public GssRootFolderResource(String host, GSSResourceFactory factory, Object resource,String path) {
		super(host, factory, resource,null);
		this.path=path;
		try {
			this.folder = (Folder) factory.getResourceGss(path,getCurrentUser());
			//log.info("ROOT FOLDER:"+folder);
		} catch (RpcException e) {
			// TODO Auto-generated catch block
		}
	}
	
	@Override
	public boolean authorise(Request request, Method method, Auth auth) {
		// TODO Auto-generated method stub
		boolean result = factory.getSecurityManager().authorise(request, method, auth, this);
        if(result){
        	User user = getCurrentUser();
        	if(user==null)
        		//log.info("AUTH USER NULL");
        	if(this.folder==null){
	        	try {
	        			this.folder= (Folder) factory.getResourceGss(path,getCurrentUser());//getService().getFolder(user.getId(), folder.getId());
				} catch (RpcException e) {
					//log.info("*****AUTH1:"+false+" "+getCurrentUser());
					return false;
				}
        	}
			//log.info("*****AUTH2:"+true+" "+getCurrentUser());
			return true;
        }
        //log.info("*****AUTH3:"+result+" "+getCurrentUser()+" "+method);
        return result;
    }
	
	
	@Override
	public Object authenticate(DigestResponse digestRequest) {
		// TODO Auto-generated method stub
		return super.authenticate(digestRequest);
	}
	
	@Override
	public String getName() {
		return path;
	}
	@Override
	public String getUniqueId() {
		if(folder!=null)
			return "folder:"+folder.getId().toString();
		return "folder:"+path;
	}
	@Override
	public Resource child(String name) {
		log.debug("CALLING ROOT GET CHILD:"+getCurrentUser());
		if(this.folder==null)
			try {
				this.folder = (Folder) factory.getResourceGss(path,getCurrentUser());
			} catch (RpcException e) {
				// 
			}
		return super.child(name);
	}
	@Override
	public List<? extends Resource> getChildren() {
		//log.info("CALLING ROOT GET CHILDREN:"+getCurrentUser());
		if(this.folder==null)
			try {
				this.folder = (Folder) factory.getResourceGss(path,getCurrentUser());
			} catch (RpcException e) {
				// 
			}
		List<Resource> result = new ArrayList<Resource>();
		for(Folder f : folder.getSubfolders())
			if(!f.isDeleted())
				result.add(new GssFolderResource(host, factory, f,getCurrentUser()));
		if(folder.getParent()==null)
			result.add(new GssOthersResource(getHost(), factory));
		try {
			for(FileHeader f : getService().getFiles(getCurrentUser().getId(), folder.getId(), true))
				result.add(new GssFileResource(host, factory, f,getCurrentUser()));
		} catch (ObjectNotFoundException e) {
			// 
			
		} catch (InsufficientPermissionsException e) {
			// 
			
		} catch (RpcException e) {
			// 
			
		}
		//result.add(new GssOthersResource(host, factory));
		return result;
	}
	
	/*Disable Locks if folder is null*/
	public LockResult lock(LockTimeout timeout, LockInfo lockInfo) throws NotAuthorizedException {
		if(folder==null)
			throw new NotAuthorizedException(this);
        return factory.getLockManager().lock(timeout, lockInfo, this);
    }

    public LockResult refreshLock(String token) throws NotAuthorizedException {
    	if(folder==null)
			throw new NotAuthorizedException(this);
        return factory.getLockManager().refresh(token, this);
    }

    public void unlock(String tokenId) throws NotAuthorizedException {
    	if(folder==null)
			throw new NotAuthorizedException(this);
        factory.getLockManager().unlock(tokenId, this);
    }

    public LockToken getCurrentLock() {
    	if(folder==null)
			return null;
        if( factory.getLockManager() != null ) {
            return factory.getLockManager().getCurrentToken( this );
        } else {
            log.warn("getCurrentLock called, but no lock manager: file: " + resource);
            return null;
        }
    }

}
