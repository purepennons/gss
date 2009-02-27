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
package gr.ebs.gss.client.rest.resource;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONParser;


/**
 * @author kman
 *
 */
public class GroupResource extends RestResource{

	/**
	 * @param path
	 */
	public GroupResource(String path) {
		super(path);
	}

	List<String> userPaths = new ArrayList<String>();





	/**
	 * Retrieve the userPaths.
	 *
	 * @return the userPaths
	 */
	public List<String> getUserPaths() {
		return userPaths;
	}

	/**
	 * Modify the userPaths.
	 *
	 * @param userPaths the userPaths to set
	 */
	public void setUserPaths(List<String> userPaths) {
		this.userPaths = userPaths;
	}

	public void createFromJSON(String text) {
		JSONArray array = (JSONArray) JSONParser.parse(text);
		for (int i = 0; i < array.size(); i++)
			getUserPaths().add(array.get(i).isString().stringValue());

	}

	public String getName(){
		String[] names = path.split("/");
		return names[names.length -1];
	}


}