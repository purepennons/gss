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

import gr.ebs.gss.client.domain.GroupDTO;
import gr.ebs.gss.client.domain.UserDTO;
import gr.ebs.gss.client.exceptions.RpcException;

import java.util.Iterator;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.AbstractImagePrototype;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.KeyboardListener;
import com.google.gwt.user.client.ui.Tree;
import com.google.gwt.user.client.ui.TreeImages;
import com.google.gwt.user.client.ui.TreeItem;
import com.google.gwt.user.client.ui.TreeListener;

/**
 * A component that displays a list of the user's groups.
 */
public class Groups extends Composite implements TreeListener {

	/**
	 * An image bundle for this widget.
	 */
	public interface Images extends TreeImages, FileMenu.Images, EditMenu.Images, GroupMenu.Images, MessagePanel.Images {

		/**
		 * Will bundle the file 'groupevent.png' residing in the package
		 * 'gr.ebs.gss.resources'.
		 *
		 * @return the image prototype
		 */
		@Resource("gr/ebs/gss/resources/groupevent.png")
		AbstractImagePrototype groupImage();

		@Resource("gr/ebs/gss/resources/editdelete.png")
		AbstractImagePrototype delete();
	}
	private boolean ctrlKeyPressed = false;

	private boolean leftClicked = false;

	private boolean rightClicked = false;


	/**
	 * The tree widget that displays the groups.
	 */
	private Tree tree;

	/**
	 * A cached copy of the currently selected group widget.
	 */
	private TreeItem current;

	/**
	 * A cached copy of the previously selected group widget.
	 */
	private TreeItem previous;

	/**
	 * A cached copy of the currently changed group widget.
	 */
	private TreeItem changed;

	/**
	 * The widget's image bundle.
	 */
	private final Images images;

	private GroupContextMenu menu;
	/**
	 * Constructs a new groups widget with a bundle of images.
	 *
	 * @param newImages a bundle that provides the images for this widget
	 */
	public Groups(final Images newImages) {

		images = newImages;
		menu = new GroupContextMenu(images);
		tree = new Tree(newImages);
		tree.addTreeListener(this);
		initWidget(tree);
		setStylePrimaryName("gss-Groups");
		sinkEvents(Event.ONCONTEXTMENU);
		sinkEvents(Event.ONMOUSEUP);
	}

	public void onBrowserEvent(Event event) {
		switch (DOM.eventGetType(event)) {
			case Event.ONKEYDOWN:
				int key = DOM.eventGetKeyCode(event);
				if (key == KeyboardListener.KEY_CTRL)
					ctrlKeyPressed = true;
				break;

			case Event.ONKEYUP:
				key = DOM.eventGetKeyCode(event);
				if (key == KeyboardListener.KEY_CTRL)
					ctrlKeyPressed = false;
				break;

			case Event.ONMOUSEDOWN:
				if (DOM.eventGetButton(event) == Event.BUTTON_RIGHT)
					rightClicked = true;
				else if (DOM.eventGetButton(event) == Event.BUTTON_LEFT)
					leftClicked = true;
				break;

			case Event.ONMOUSEUP:
				if (DOM.eventGetButton(event) == Event.BUTTON_RIGHT)
					rightClicked = false;
				else if (DOM.eventGetButton(event) == Event.BUTTON_LEFT)
					leftClicked = false;
				break;
		}

		super.onBrowserEvent(event);
	}
	/**
	 * Make an RPC call to retrieve the groups that belong to the specified
	 * user.
	 *
	 * @param userId the user ID
	 */
	public void updateGroups(final Long userId) {
		GSS.get().showLoadingIndicator();
		final GSSServiceAsync service = GSS.get().getRemoteService();
		service.getGroups(userId, new AsyncCallback() {

			public void onSuccess(final Object result) {
				final List<GroupDTO> groupList = (List<GroupDTO>) result;
				GWT.log("got " + groupList.size() + " groups", null);
				tree.clear();
				for (int i = 0; i < groupList.size(); i++) {
					final TreeItem item = new TreeItem(imageItemHTML(images.groupImage(), groupList.get(i).getName()));
					item.setUserObject(groupList.get(i));
					tree.addItem(item);
					final Iterator iter = groupList.get(i).getMembers().iterator();
					while (iter.hasNext()) {
						final UserDTO user = (UserDTO) iter.next();
						final TreeItem userItem = addImageItem(item, user.getName() + " &lt;" + user.getEmail() + "&gt;", images.permUser());
						userItem.setUserObject(user);
					}
				}
				GSS.get().hideLoadingIndicator();
			}

			public void onFailure(final Throwable caught) {
				GWT.log("", caught);
				GSS.get().hideLoadingIndicator();
				if (caught instanceof RpcException)
					GSS.get().displayError("An error occurred while " + "communicating with the server: " + caught.getMessage());
				else
					GSS.get().displayError(caught.getMessage());
			}
		});
	}

	/**
	 * A helper method to simplify adding tree items that have attached images.
	 * {@link #addImageItem(TreeItem, String) code}
	 *
	 * @param parent the tree item to which the new item will be added.
	 * @param title the text associated with this item.
	 * @param imageProto
	 * @return the new tree item
	 */
	private TreeItem addImageItem(final TreeItem parent, final String title, final AbstractImagePrototype imageProto) {
		final TreeItem item = new TreeItem(imageItemHTML(imageProto, title));
		parent.addItem(item);
		return item;
	}

	/**
	 * Generates HTML for a tree item with an attached icon.
	 *
	 * @param imageProto the icon image
	 * @param title the title of the item
	 * @return the resultant HTML
	 */
	private HTML imageItemHTML(final AbstractImagePrototype imageProto, final String title) {
		final HTML link = new HTML("<a class='hidden-link' href='javascript:;'>" + "<span>" + imageProto.getHTML() + "&nbsp;" + title + "</span>" + "</a>");
		return link;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see com.google.gwt.user.client.ui.TreeListener#onTreeItemSelected(com.google.gwt.user.client.ui.TreeItem)
	 */
	public void onTreeItemSelected(final TreeItem item) {
		final Object selected = item.getUserObject();
		// Preserve the previously selected item, so that the current's
		// onClick() method gets a chance to find it.
		if (getPrevious() != null)
			getPrevious().getWidget().removeStyleName("gss-SelectedRow");
		setCurrent(item);
		getCurrent().getWidget().addStyleName("gss-SelectedRow");
		setPrevious(getCurrent());
		GSS.get().setCurrentSelection(selected);
		if (rightClicked) {
			int left = item.getAbsoluteLeft() + 40;
			int top = item.getAbsoluteTop() + 20;
			showPopup(left, top);
		}
	}

	protected void showPopup(final int x, final int y) {
		if (getCurrent() == null)
			return;
		menu.hide();
		menu = new GroupContextMenu(images);
		menu.setPopupPosition(x, y);
		menu.show();
	}
	/*
	 * (non-Javadoc)
	 *
	 * @see com.google.gwt.user.client.ui.TreeListener#onTreeItemStateChanged(com.google.gwt.user.client.ui.TreeItem)
	 */
	public void onTreeItemStateChanged(final TreeItem item) {
		// Ignore closed items.
		if (!item.getState())
			return;

		setChanged(item);
		updateUsers(GSS.get().getCurrentUser().getId(), item);
	}

	/**
	 * Generate an RPC request to retrieve the users of the specified group for
	 * display.
	 *
	 * @param userId the ID of the current user
	 * @param groupItem the TreeItem widget that corresponds to the requested
	 *            group
	 */
	void updateUsers(final Long userId, final TreeItem groupItem) {
		GSS.get().showLoadingIndicator();
		final GSSServiceAsync service = GSS.get().getRemoteService();
		final GroupDTO g = (GroupDTO) groupItem.getUserObject();
		final Long groupId = g.getId();
		service.getUsers(userId, groupId, new AsyncCallback() {

			public void onSuccess(final Object result) {
				final List<UserDTO> users = (List<UserDTO>) result;
				final GroupDTO changedGroup = (GroupDTO) groupItem.getUserObject();
				GWT.log("'" + changedGroup.getName() + "' has " + users.size() + " users", null);
				// Clear the previous users before introducing the new ones.
				groupItem.removeItems();

				for (int i = 0; i < users.size(); i++) {
					final TreeItem userItem = addImageItem(groupItem, users.get(i).getName() + " &lt;" + users.get(i).getEmail() + "&gt;", images.permUser());
					userItem.setUserObject(users.get(i));
				}
				GSS.get().hideLoadingIndicator();
			}

			public void onFailure(final Throwable caught) {
				GWT.log("", caught);
				GSS.get().hideLoadingIndicator();
				if (caught instanceof RpcException)
					GSS.get().displayError("An error occurred while " + "communicating with the server: " + caught.getMessage());
				else
					GSS.get().displayError(caught.getMessage());
			}
		});
	}

	/**
	 * Retrieve the current.
	 *
	 * @return the current
	 */
	TreeItem getCurrent() {
		return current;
	}

	/**
	 * Modify the current.
	 *
	 * @param newCurrent the current to set
	 */
	void setCurrent(final TreeItem newCurrent) {
		current = newCurrent;
	}

	/**
	 * Modify the changed.
	 *
	 * @param newChanged the changed to set
	 */
	private void setChanged(final TreeItem newChanged) {
		changed = newChanged;
	}

	/**
	 * Retrieve the previous.
	 *
	 * @return the previous
	 */
	private TreeItem getPrevious() {
		return previous;
	}

	/**
	 * Modify the previous.
	 *
	 * @param newPrevious the previous to set
	 */
	private void setPrevious(final TreeItem newPrevious) {
		previous = newPrevious;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see com.google.gwt.user.client.ui.UIObject#setVisible(boolean)
	 */
	public void setVisible(final boolean visible) {
		super.setVisible(visible);
		if (visible)
			updateGroups(GSS.get().getCurrentUser().getId());
	}
}
