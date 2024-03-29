/*
 * Copyright 2007, 2008, 2009 Electronic Business Systems Ltd.
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
package org.gss_project.gss.web.client;

import org.gss_project.gss.web.client.commands.CopyCommand;
import org.gss_project.gss.web.client.commands.CutCommand;
import org.gss_project.gss.web.client.commands.DeleteCommand;
import org.gss_project.gss.web.client.commands.EmptyTrashCommand;
import org.gss_project.gss.web.client.commands.NewFolderCommand;
import org.gss_project.gss.web.client.commands.PasteCommand;
import org.gss_project.gss.web.client.commands.PropertiesCommand;
import org.gss_project.gss.web.client.commands.RefreshCommand;
import org.gss_project.gss.web.client.commands.RestoreTrashCommand;
import org.gss_project.gss.web.client.commands.ToTrashCommand;
import org.gss_project.gss.web.client.commands.UploadFileCommand;
import org.gss_project.gss.web.client.rest.resource.MyFolderResource;
import org.gss_project.gss.web.client.rest.resource.OtherUserResource;
import org.gss_project.gss.web.client.rest.resource.OthersFolderResource;
import org.gss_project.gss.web.client.rest.resource.OthersResource;
import org.gss_project.gss.web.client.rest.resource.RestResource;
import org.gss_project.gss.web.client.rest.resource.SharedFolderResource;
import org.gss_project.gss.web.client.rest.resource.SharedResource;
import org.gss_project.gss.web.client.rest.resource.TrashFolderResource;
import org.gss_project.gss.web.client.rest.resource.TrashResource;

import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.user.client.ui.AbstractImagePrototype;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.PopupPanel;

/**
 * The 'Folder Context' menu implementation.
 */
public class FolderContextMenu extends PopupPanel {

	/**
	 * The widget's images.
	 */
	private final Images images;

	/**
	 * The image bundle for this widget's images that reuses images defined in
	 * other menus.
	 */
	public interface Images extends ClientBundle,FileMenu.Images, EditMenu.Images {
	}

	private MenuItem pasteItem;

	/**
	 * The widget's constructor.
	 *
	 * @param newImages the image bundle passed on by the parent object
	 */
	public FolderContextMenu(final Images newImages) {
		// The popup's constructor's argument is a boolean specifying that it
		// auto-close itself when the user clicks outside of it.
		super(true);
		setAnimationEnabled(true);
		images = newImages;

		pasteItem = new MenuItem("<span id = 'folderContextMenu.paste'>" + AbstractImagePrototype.create(newImages.paste()).getHTML() + "&nbsp;Paste</span>", true, new PasteCommand(this));
		MenuBar contextMenu = new MenuBar(true);
		
		RestResource selectedItem = GSS.get().getTreeView().getSelection();

		if(selectedItem != null)
			if(selectedItem instanceof MyFolderResource){		
				MenuItem newFolder = new MenuItem("<span id = 'folderContextMenu.newFolder'>" + AbstractImagePrototype.create(newImages.folderNew()).getHTML() + "&nbsp;New Folder</span>", true, new NewFolderCommand(this, images));
				contextMenu.addItem(newFolder);
				
				MenuItem upload = new MenuItem("<span id = 'folderContextMenu.upload'>" + AbstractImagePrototype.create(newImages.fileUpdate()).getHTML() + "&nbsp;Upload</span>", true, new UploadFileCommand(this));
				contextMenu.addItem(upload);
											
				boolean notRootFolder =(((MyFolderResource) selectedItem).getResource()).getParentURI() != null ? true : false;	
				if (notRootFolder) {
					// do not show the copy & cut option for the user's root folder					
					MenuItem cut = new MenuItem("<span id = 'folderContextMenu.cut'>" + AbstractImagePrototype.create(newImages.cut()).getHTML() + "&nbsp;Cut</span>", true, new CutCommand(this));
					contextMenu.addItem(cut);
					
				}
				MenuItem copy = new MenuItem("<span id = 'folderContextMenu.copy'>" + AbstractImagePrototype.create(newImages.copy()).getHTML() + "&nbsp;Copy</span>", true, new CopyCommand(this));					
				contextMenu.addItem(copy);
				contextMenu.addItem(pasteItem);
				if (notRootFolder) {
					// do not show delete options for the user's root folder
					MenuItem moveToTrash = new MenuItem("<span id = 'folderContextMenu.moveToTrash'>" + AbstractImagePrototype.create(newImages.emptyTrash()).getHTML() + "&nbsp;Move to Trash</span>", true, new ToTrashCommand(this));					
					contextMenu.addItem(moveToTrash);
					
					MenuItem delete = new MenuItem("<span id = 'folderContextMenu.delete'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
					contextMenu.addItem(delete);					
				}
				
				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.refresh'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
				MenuItem sharing = new MenuItem("<span id = 'folderContextMenu.sharing'>" + AbstractImagePrototype.create(newImages.sharing()).getHTML() + "&nbsp;Sharing</span>", true, new PropertiesCommand(this, newImages, 1));
				contextMenu.addItem(sharing);
				
				MenuItem properties = new MenuItem("<span id = 'folderContextMenu.properties'>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Properties</span>", true, new PropertiesCommand(this, newImages, 0));
				contextMenu.addItem(properties);		
			}
		
			if(selectedItem instanceof SharedFolderResource){
				MenuItem newFolder = new MenuItem("<span id = 'folderContextMenu.newFolder'>" + AbstractImagePrototype.create(newImages.folderNew()).getHTML() + "&nbsp;New Folder</span>", true, new NewFolderCommand(this, images));
				contextMenu.addItem(newFolder);
				
				MenuItem upload = new MenuItem("<span id = 'folderContextMenu.upload'>" + AbstractImagePrototype.create(newImages.fileUpdate()).getHTML() + "&nbsp;Upload</span>", true, new UploadFileCommand(this));
				contextMenu.addItem(upload);
				
				MenuItem cut = new MenuItem("<span id = 'folderContextMenu.cut'>" + AbstractImagePrototype.create(newImages.cut()).getHTML() + "&nbsp;Cut</span>", true, new CutCommand(this));
				contextMenu.addItem(cut);
				
				MenuItem copy = new MenuItem("<span id = 'folderContextMenu.copy'>" + AbstractImagePrototype.create(newImages.copy()).getHTML() + "&nbsp;Copy</span>", true, new CopyCommand(this));
				contextMenu.addItem(copy);
				
				contextMenu.addItem(pasteItem);
				
				MenuItem moveToTrash = new MenuItem("<span id = 'folderContextMenu.moveToTrash'>" + AbstractImagePrototype.create(newImages.emptyTrash()).getHTML() + "&nbsp;Move to Trash</span>", true, new ToTrashCommand(this));
				contextMenu.addItem(moveToTrash);
				
				MenuItem delete = new MenuItem("<span id = 'folderContextMenu.delete'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
				contextMenu.addItem(delete);

				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.refresh'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
				MenuItem sharing = new MenuItem("<span id = 'folderContextMenu.sharing'>" + AbstractImagePrototype.create(newImages.sharing()).getHTML() + "&nbsp;Sharing</span>", true, new PropertiesCommand(this, newImages, 1));
				contextMenu.addItem(sharing);
				
				MenuItem properties = new MenuItem("<span id = 'folderContextMenu.properties'>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Properties</span>", true, new PropertiesCommand(this, newImages, 0));
				contextMenu.addItem(properties);
				
			}
			if(selectedItem instanceof TrashFolderResource){
				MenuItem restore = new MenuItem("<span id = 'folderContextMenu.restore'>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Restore folder and contents</span>", true, new RestoreTrashCommand(this));
				contextMenu.addItem(restore);
				
				MenuItem delete = new MenuItem("<span id = 'folderContextMenu.delete'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
				contextMenu.addItem(delete);
				
				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.refresh'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
			}
			if(selectedItem instanceof OthersFolderResource){				
				MenuItem newFolder = new MenuItem("<span id = 'folderContextMenu.newFolder'>" + AbstractImagePrototype.create(newImages.folderNew()).getHTML() + "&nbsp;New Folder</span>", true, new NewFolderCommand(this, images));
				contextMenu.addItem(newFolder);

				MenuItem upload = new MenuItem("<span id ='folderContextMenu.upload'>" + AbstractImagePrototype.create(newImages.fileUpdate()).getHTML() + "&nbsp;Upload</span>", true, new UploadFileCommand(this));
				contextMenu.addItem(upload);
				
				MenuItem cut = new MenuItem("<span id = 'folderContextMenu.cut'>" + AbstractImagePrototype.create(newImages.cut()).getHTML() + "&nbsp;Cut</span>", true, new CutCommand(this));
				contextMenu.addItem(cut);
				
				MenuItem copy = new MenuItem("<span id = 'folderContextMenu.copy'>" + AbstractImagePrototype.create(newImages.copy()).getHTML() + "&nbsp;Copy</span>", true, new CopyCommand(this));
				contextMenu.addItem(copy);
				
				contextMenu.addItem(pasteItem);
				
				MenuItem moveToTrash = new MenuItem("<span id = 'folderContextMenu.moveToTrash'>" + AbstractImagePrototype.create(newImages.emptyTrash()).getHTML() + "&nbsp;Move to Trash</span>", true, new ToTrashCommand(this));
				contextMenu.addItem(moveToTrash);
				
				MenuItem delete = new MenuItem("<span id = 'folderContextMenu.delete'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
				contextMenu.addItem(delete);

				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.delete'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
				MenuItem sharing = new MenuItem("<span id = 'folderContextMenu.sharing'>" + AbstractImagePrototype.create(newImages.sharing()).getHTML() + "&nbsp;Sharing</span>", true, new PropertiesCommand(this, newImages, 1));
				contextMenu.addItem(sharing);
				
				MenuItem properties = new MenuItem("<span id = 'folderContextMenu.properties'>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Properties</span>", true, new PropertiesCommand(this, newImages, 0));
				contextMenu.addItem(properties);
				
			}
			if(selectedItem instanceof TrashResource){
				MenuItem restore = new MenuItem("<span id ='folderContextMenu.restore'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Restore Trash</span>", true, new RestoreTrashCommand(this));
				contextMenu.addItem(restore);
				
				MenuItem emptyTrash = new MenuItem("<span id = 'folderContextMenu.emptyTrash'>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Empty Trash</span>", true, new EmptyTrashCommand(this));
				contextMenu.addItem(emptyTrash);
				
				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.refresh'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
			}
			if(selectedItem instanceof SharedResource || selectedItem instanceof OthersResource || selectedItem instanceof OtherUserResource){
				MenuItem refresh = new MenuItem("<span id = 'folderContextMenu.refresh'>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem(refresh);
				
			}
			
			/*
			if(folders.isTrashItem(selectedItem)){
				if (folders.isTrash(selectedItem)){
					contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Empty Trash</span>", true, new EmptyTrashCommand(this));
					contextMenu.addItem("<span>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				}
				else {
					contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Restore folder and contents</span>", true, new RestoreTrashCommand(this));
					contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
					contextMenu.addItem("<span>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				}
			}
			else if(folders.isFileItem(selectedItem)){
				
			}
			else if(!folders.isMyShares(selectedItem) && folders.isMySharedItem(selectedItem)){
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.folderNew()).getHTML() + "&nbsp;New Folder</span>", true, new NewFolderCommand(this, images));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.fileUpdate()).getHTML() + "&nbsp;Upload</span>", true, new UploadFileCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.cut()).getHTML() + "&nbsp;Cut</span>", true, new CutCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.copy()).getHTML() + "&nbsp;Copy</span>", true, new CopyCommand(this));
				contextMenu.addItem(pasteItem);
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.emptyTrash()).getHTML() + "&nbsp;Move to Trash</span>", true, new ToTrashCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.sharing()).getHTML() + "&nbsp;Sharing</span>", true, new PropertiesCommand(this, newImages, 1));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Properties</span>", true, new PropertiesCommand(this, newImages, 0));
			}
			else if(!folders.isOthersShared(selectedItem) && folders.isOthersSharedItem(selectedItem) && !(GSS.get().getCurrentSelection() instanceof OtherUserResource)){
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.folderNew()).getHTML() + "&nbsp;New Folder</span>", true, new NewFolderCommand(this, images));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.fileUpdate()).getHTML() + "&nbsp;Upload</span>", true, new UploadFileCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.cut()).getHTML() + "&nbsp;Cut</span>", true, new CutCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.copy()).getHTML() + "&nbsp;Copy</span>", true, new CopyCommand(this));
				contextMenu.addItem(pasteItem);
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.emptyTrash()).getHTML() + "&nbsp;Move to Trash</span>", true, new ToTrashCommand(this));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.delete()).getHTML() + "&nbsp;Delete</span>", true, new DeleteCommand(this, newImages));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.sharing()).getHTML() + "&nbsp;Sharing</span>", true, new PropertiesCommand(this, newImages, 1));
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(newImages.viewText()).getHTML() + "&nbsp;Properties</span>", true, new PropertiesCommand(this, newImages, 0));
			} else if(!selectedItem.equals(folders.getSharesItem()))
				contextMenu.addItem("<span>" + AbstractImagePrototype.create(images.refresh()).getHTML() + "&nbsp;Refresh</span>", true, new RefreshCommand(this, images));
			*/
		
		add(contextMenu);
		if (GSS.get().getClipboard().hasFolderOrFileItem())
			pasteItem.setVisible(true);
		else
			pasteItem.setVisible(false);
	}

}
