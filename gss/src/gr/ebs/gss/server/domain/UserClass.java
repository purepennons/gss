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
package gr.ebs.gss.server.domain;

import gr.ebs.gss.client.domain.UserClassDTO;

import java.io.Serializable;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Version;

/**
 * A group of users with common attributes.
 *
 * @author droutsis
 */
@Entity
public class UserClass  implements Serializable{

	/**
	 * The persistence ID of the object.
	 */
	@Id
	@GeneratedValue
	private Long id;

	/**
	 * Version field for optimistic locking.
	 */
	@SuppressWarnings("unused")
	@Version
	private int version;

	/**
	 * The audit information.
	 */
	@SuppressWarnings("unused")
	@Embedded
	private AuditInfo auditInfo;

	/**
	 * A name for this class.
	 */
	private String name;

	/**
	 * The disk quota of this user class.
	 */
	private long quota;

	/**
	 * The users belonging to this class
	 */
	@OneToMany(cascade = CascadeType.ALL, mappedBy = "userClass")
	private List<User> users;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return name;
	}

	/**
	 * Return a new Data Transfer Object for this user class.
	 *
	 * @return a new DTO with the same contents as this object
	 */
	public UserClassDTO getDTO() {
		final UserClassDTO u = new UserClassDTO();
		u.setId(id);
		u.setName(name);
		u.setQuota(quota);
		for (final User user : users)
			u.getUsers().add(user.getDTO());
		return u;
	}
}
