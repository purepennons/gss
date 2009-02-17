/*
 * Copyright 2008, 2009 Electronic Business Systems Ltd.
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
package gr.ebs.gss.client.commands;

import gr.ebs.gss.client.GSS;
import gr.ebs.gss.client.clipboard.ClipboardItem;
import gr.ebs.gss.client.domain.FileHeaderDTO;
import gr.ebs.gss.client.domain.FolderDTO;
import gr.ebs.gss.client.domain.GroupDTO;
import gr.ebs.gss.client.domain.UserDTO;

import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.PopupPanel;
/**
 *
 * Command for copying a file, folder or user to GSS Clipboard
 * @author kman
 *
 */
public class CopyCommand implements Command{
	private PopupPanel containerPanel;

	public CopyCommand(PopupPanel _containerPanel){
		containerPanel = _containerPanel;
	}
	/* (non-Javadoc)
	 * @see com.google.gwt.user.client.Command#execute()
	 */
	public void execute() {
		containerPanel.hide();
		Object selection = GSS.get().getCurrentSelection();
		if (selection == null)
			return;
		if (selection instanceof FolderDTO) {
			ClipboardItem clipboardItem = new ClipboardItem((FolderDTO) selection);
			GSS.get().getClipboard().setItem(clipboardItem);
		} else if (selection instanceof FileHeaderDTO) {
			ClipboardItem clipboardItem = new ClipboardItem((FileHeaderDTO) selection);
			GSS.get().getClipboard().setItem(clipboardItem);
		} else if (selection instanceof UserDTO) {
			ClipboardItem clipboardItem = new ClipboardItem((UserDTO) selection);
			GSS.get().getClipboard().setItem(clipboardItem);
		} else if (selection instanceof GroupDTO) {
			// no copy for groups
		}
		 else if (selection instanceof List){
			 GWT.log("SELECTION IS A LIST", null);
			 ClipboardItem clipboardItem = new ClipboardItem((List<FileHeaderDTO>) selection);
			 GSS.get().getClipboard().setItem(clipboardItem);
		 }
	}

}
