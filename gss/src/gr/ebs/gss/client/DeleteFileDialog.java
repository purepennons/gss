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
package gr.ebs.gss.client;

import gr.ebs.gss.client.MessagePanel.Images;
import gr.ebs.gss.client.domain.FileHeaderDTO;
import gr.ebs.gss.client.exceptions.RpcException;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.ClickListener;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.KeyboardListener;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;

/**
 * The 'delete file' dialog box.
 */
public class DeleteFileDialog extends DialogBox {

	/**
	 * The widget's constructor.
	 *
	 * @param images the supplied images
	 */
	public DeleteFileDialog(final Images images) {
		// Use this opportunity to set the dialog's caption.
		setText("Delete file");
		setAnimationEnabled(true);
		Object selection = GSS.get().getCurrentSelection();
		// Create a VerticalPanel to contain the 'about' label and the 'OK'
		// button.
		final VerticalPanel outer = new VerticalPanel();
		final HorizontalPanel buttons = new HorizontalPanel();

		// Create the 'about' text and set a style name so we can style it with
		// CSS.
		final HTML text;
		if (selection instanceof FileHeaderDTO)
			text = new HTML("<table><tr><td rowspan='2'>" + images.warn().getHTML() + "</td><td>" + "Are you sure you want to delete file '" + ((FileHeaderDTO) selection).getName() + "'?</td></tr><tr><td>" + "(it will be deleted <b>permanently</b>!)" + "</td></tr></table>");
		else
			text = new HTML("<table><tr><td rowspan='2'>" + images.warn().getHTML() + "</td><td>" + "Are you sure you want to delete selected files? " + "?</td></tr><tr><td>" + "(it will be deleted <b>permanently</b>!)" + "</td></tr></table>");
		text.setStyleName("gss-warnMessage");
		outer.add(text);

		// Create the 'Quit' button, along with a listener that hides the dialog
		// when the button is clicked and quits the application.
		final Button ok = new Button("Delete the file", new ClickListener() {

			public void onClick(Widget sender) {
				deleteFile(GSS.get().getCurrentUser().getId());
				hide();
			}
		});
		buttons.add(ok);
		buttons.setCellHorizontalAlignment(ok, HasHorizontalAlignment.ALIGN_CENTER);
		// Create the 'Cancel' button, along with a listener that hides the
		// dialog
		// when the button is clicked.
		final Button cancel = new Button("Cancel", new ClickListener() {

			public void onClick(Widget sender) {
				hide();
			}
		});
		buttons.add(cancel);
		buttons.setCellHorizontalAlignment(cancel, HasHorizontalAlignment.ALIGN_CENTER);
		buttons.setSpacing(8);
		buttons.setStyleName("gss-warnMessage");
		outer.setStyleName("gss-warnMessage");
		outer.add(buttons);
		outer.setCellHorizontalAlignment(text, HasHorizontalAlignment.ALIGN_CENTER);
		outer.setCellHorizontalAlignment(buttons, HasHorizontalAlignment.ALIGN_CENTER);
		setWidget(outer);
	}

	/**
	 * Generate an RPC request to delete a file.
	 *
	 * @param userId the ID of the current user
	 */
	private void deleteFile(final Long userId) {
		final GSSServiceAsync service = GSS.get().getRemoteService();
		final Object selection = GSS.get().getCurrentSelection();
		if (selection == null) {
			GSS.get().displayError("No file was selected!");
			return;
		}
		if (selection instanceof FileHeaderDTO) {
			FileHeaderDTO file = (FileHeaderDTO) selection;
			final Long fileId = file.getId();
			GWT.log("deleteFile(" + userId + "," + fileId + ")", null);
			GSS.get().showLoadingIndicator();
			service.deleteFile(userId, fileId, new AsyncCallback() {

				public void onSuccess(final Object result) {
					GSS.get().getFileList().updateFileCache(GSS.get().getCurrentUser().getId());
					GSS.get().getStatusPanel().updateStats();
					GSS.get().hideLoadingIndicator();
				}

				public void onFailure(final Throwable caught) {
					GWT.log("", caught);
					if (caught instanceof RpcException)
						GSS.get().displayError("An error occurred while " + "communicating with the server: " + caught.getMessage());
					else
						GSS.get().displayError(caught.getMessage());
					GSS.get().hideLoadingIndicator();
				}
			});
		}
		else if(selection instanceof List){
			List<FileHeaderDTO> files = (List<FileHeaderDTO>) selection;
			final List<Long> fileIds = new ArrayList<Long>();
			for(FileHeaderDTO f : files)
				fileIds.add(f.getId());
			GSS.get().showLoadingIndicator();
			service.deleteFiles(userId, fileIds, new AsyncCallback() {

				public void onSuccess(final Object result) {
					GSS.get().getFileList().updateFileCache(GSS.get().getCurrentUser().getId());
					GSS.get().getStatusPanel().updateStats();
					GSS.get().hideLoadingIndicator();
				}

				public void onFailure(final Throwable caught) {
					GWT.log("", caught);
					if (caught instanceof RpcException)
						GSS.get().displayError("An error occurred while " + "communicating with the server: " + caught.getMessage());
					else
						GSS.get().displayError(caught.getMessage());
					GSS.get().hideLoadingIndicator();
				}
			});
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see com.google.gwt.user.client.ui.PopupPanel#onKeyDownPreview(char, int)
	 */
	public boolean onKeyDownPreview(final char key, final int modifiers) {
		// Use the popup's key preview hooks to close the dialog when either
		// enter or escape is pressed.
		switch (key) {
			case KeyboardListener.KEY_ENTER:
				hide();
				deleteFile(GSS.get().getCurrentUser().getId());
				break;
			case KeyboardListener.KEY_ESCAPE:
				hide();
				break;
		}

		return true;
	}

}
