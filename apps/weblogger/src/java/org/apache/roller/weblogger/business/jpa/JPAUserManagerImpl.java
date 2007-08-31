
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.business.jpa;

import java.sql.Timestamp;
import javax.persistence.NoResultException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Comparator;
import javax.persistence.Query;
import org.apache.roller.weblogger.business.Weblogger;


@com.google.inject.Singleton
public class JPAUserManagerImpl implements UserManager {
    
    /** The logger instance for this class. */
    private static Log log = LogFactory.getLog(JPAUserManagerImpl.class);
    
    private static final Comparator statCountCountReverseComparator =
            Collections.reverseOrder(StatCountCountComparator.getInstance());
    
    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;
    
    // cached mapping of weblogHandles -> weblogIds
    private Map weblogHandleToIdMap = new Hashtable();
    
    // cached mapping of userNames -> userIds
    private Map userNameToIdMap = new Hashtable();
    
    
    @com.google.inject.Inject
    protected JPAUserManagerImpl(Weblogger roller, JPAPersistenceStrategy strat) {
        log.debug("Instantiating JPA User Manager");
        this.roller = roller;
        this.strategy = strat;
    }
    
    
    public void release() {}
    
    
    //--------------------------------------------------------------- user CRUD
 
    public void saveUser(User data) throws WebloggerException {
        this.strategy.store(data);
    }
    
    
    public void removeUser(User user) throws WebloggerException {
        //remove permissions
        // make sure that both sides of the relationship are maintained
        List<WeblogPermission> perms = getWeblogPermissions(user);
        for (WeblogPermission perm : perms) {
           
            //Remove it from database
            this.strategy.remove(perms);
        }
        
        this.strategy.remove(user);
        
        // remove entry from cache mapping
        this.userNameToIdMap.remove(user.getUserName());
    }
    
    
    public void addUser(User newUser) throws WebloggerException {
        
        if(newUser == null)
            throw new WebloggerException("cannot add null user");
        
        // TODO BACKEND: we must do this in a better fashion, like getUserCnt()?
        boolean adminUser = false;
        List existingUsers = this.getUsers(Boolean.TRUE, null, null, 0, 1);
        if (existingUsers.size() == 0) {
            // Make first user an admin
            adminUser = true;
            
            //if user was disabled (because of activation user 
            // account with e-mail property), enable it for admin user
            newUser.setEnabled(Boolean.TRUE);
            newUser.setActivationCode(null);
        }
        
        if (getUserByUserName(newUser.getUserName()) != null ||
                getUserByUserName(newUser.getUserName().toLowerCase()) != null) {
            throw new WebloggerException("error.add.user.userNameInUse");
        }
                
        this.strategy.store(newUser);
        
        grantRole("editor", newUser);
        if (adminUser) {
            grantRole("admin", newUser);
        }
    }
    
    
    public User getUser(String id) throws WebloggerException {
        return (User)this.strategy.load(User.class, id);
    }
    
    
    //------------------------------------------------------------ user queries

    public User getUserByUserName(String userName) throws WebloggerException {
        return getUserByUserName(userName, Boolean.TRUE);
    }
    
    
    public User getUserByUserName(String userName, Boolean enabled)
    throws WebloggerException {
        
        if (userName==null )
            throw new WebloggerException("userName cannot be null");
        
        // check cache first
        // NOTE: if we ever allow changing usernames then this needs updating
        if(this.userNameToIdMap.containsKey(userName)) {
            
            User user = this.getUser(
                    (String) this.userNameToIdMap.get(userName));
            if(user != null) {
                // only return the user if the enabled status matches
                if(enabled == null || enabled.equals(user.getEnabled())) {
                    log.debug("userNameToIdMap CACHE HIT - "+userName);
                    return user;
                }
            } else {
                // mapping hit with lookup miss?  mapping must be old, remove it
                this.userNameToIdMap.remove(userName);
            }
        }
        
        // cache failed, do lookup
        Query query;
        Object[] params;
        if (enabled != null) {
            query = strategy.getNamedQuery(
                    "User.getByUserName&Enabled");
            params = new Object[] {userName, enabled};
        } else {
            query = strategy.getNamedQuery(
                    "User.getByUserName");
            params = new Object[] {userName};
        }
        for (int i=0; i<params.length; i++) {
            query.setParameter(i+1, params[i]);
        }
        User user = null;
        try {
            user = (User)query.getSingleResult();
        } catch (NoResultException e) {
            user = null;
        }
        
        // add mapping to cache
        if(user != null) {
            log.debug("userNameToIdMap CACHE MISS - "+userName);
            this.userNameToIdMap.put(user.getUserName(), user.getId());
        }
        
        return user;
    }
    
    
    public List getUsers(Weblog weblog, Boolean enabled, Date startDate,
            Date endDate, int offset, int length)
            throws WebloggerException {
        Query query = null;
        List results = null;
        
        // if we are doing date range then we must have an end date
        if (startDate != null && endDate == null) {
            endDate = new Date();
        }

        List params = new ArrayList();
        int size = 0;
        StringBuffer queryString = new StringBuffer();
        StringBuffer whereClause = new StringBuffer();
                            
        if (weblog != null) {
            queryString.append("SELECT u FROM User u JOIN u.permissions p WHERE ");
            params.add(size++, weblog);
            whereClause.append(" p.website = ?" + size);   
        } else {
            queryString.append("SELECT u FROM User u WHERE ");
        }         

        if (enabled != null) {
            if (whereClause.length() > 0) whereClause.append(" AND ");
            params.add(size++, enabled);
            whereClause.append("u.enabled = ?" + size);  
        }

        if (startDate != null) {
            if (whereClause.length() > 0) whereClause.append(" AND ");

            // if we are doing date range then we must have an end date
            if(endDate == null) {
                endDate = new Date();
            }
            Timestamp start = new Timestamp(startDate.getTime());
            Timestamp end = new Timestamp(endDate.getTime());
            params.add(size++, start);
            whereClause.append("u.dateCreated > ?" + size);
            params.add(size++, end);
            whereClause.append(" AND u.dateCreated < ?" + size);
        }
        whereClause.append(" ORDER BY u.dateCreated DESC");
        query = strategy.getDynamicQuery(queryString.toString() + whereClause.toString());

        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        for (int i=0; i<params.size(); i++) {
           query.setParameter(i+1, params.get(i));
        }
        return query.getResultList();
    }
    
    
    public List getUsers(int offset, int length) throws WebloggerException {
        return getUsers(Boolean.TRUE, null, null, offset, length);
    }
    
    
    public List getUsers(Boolean enabled, Date startDate, Date endDate,
            int offset, int length)
            throws WebloggerException {
        Query query = null;
        List results = null;
        boolean setRange = offset != 0 || length != -1;
        
        if (endDate == null) endDate = new Date();
        
        if (enabled != null) {
            if (startDate != null) {
                Timestamp start = new Timestamp(startDate.getTime());
                Timestamp end = new Timestamp(endDate.getTime());
                query = strategy.getNamedQuery(
                        "User.getByEnabled&EndDate&StartDateOrderByStartDateDesc");
                query.setParameter(1, enabled);
                query.setParameter(2, end);
                query.setParameter(3, start);
            } else {
                Timestamp end = new Timestamp(endDate.getTime());
                query = strategy.getNamedQuery(
                        "User.getByEnabled&EndDateOrderByStartDateDesc");
                query.setParameter(1, enabled);
                query.setParameter(2, end);
            }
        } else {
            if (startDate != null) {
                Timestamp start = new Timestamp(startDate.getTime());
                Timestamp end = new Timestamp(endDate.getTime());
                query = strategy.getNamedQuery(
                        "User.getByEndDate&StartDateOrderByStartDateDesc");
                query.setParameter(1, end);
                query.setParameter(2, start);
            } else {
                Timestamp end = new Timestamp(endDate.getTime());
                query = strategy.getNamedQuery(
                        "User.getByEndDateOrderByStartDateDesc");
                query.setParameter(1, end);
            }
        }
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }
    
    
    /**
     * Get users of a website
     */
    public List getUsers(Weblog website, Boolean enabled, int offset, int length) throws WebloggerException {
        Query query = null;
        List results = null;
        boolean setRange = offset != 0 || length != -1;
        
        if (length == -1) {
            length = Integer.MAX_VALUE - offset;
        }
        
        if (enabled != null) {
            if (website != null) {
                query = strategy.getNamedQuery("User.getByEnabled&Permissions.website");
                query.setParameter(1, enabled);
                query.setParameter(2, website);
            } else {
                query = strategy.getNamedQuery("User.getByEnabled");
                query.setParameter(1, enabled);
            }
        } else {
            if (website != null) {
                query = strategy.getNamedQuery("User.getByPermissions.website");
                query.setParameter(1, website);
            } else {
                query = strategy.getNamedQuery("User.getAll");
            }
        }
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }
    
    
    public List getUsersStartingWith(String startsWith, Boolean enabled,
            int offset, int length) throws WebloggerException {
        Query query = null;
        
        if (enabled != null) {
            if (startsWith != null) {
                query = strategy.getNamedQuery(
                        "User.getByEnabled&UserNameOrEmailAddressStartsWith");
                query.setParameter(1, enabled);
                query.setParameter(2, startsWith + '%');
                query.setParameter(3, startsWith + '%');
            } else {
                query = strategy.getNamedQuery(
                        "User.getByEnabled");
                query.setParameter(1, enabled);
            }
        } else {
            if (startsWith != null) {
                query = strategy.getNamedQuery(
                        "User.getByUserNameOrEmailAddressStartsWith");
                query.setParameter(1, startsWith +  '%');
            } else {
                query = strategy.getNamedQuery("User.getAll");
            }
        }
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }
    
    
    public Map getUserNameLetterMap() throws WebloggerException {
        String lc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Map results = new TreeMap();
        Query query = strategy.getNamedQuery(
                "User.getCountByUserNameLike");
        for (int i=0; i<26; i++) {
            char currentChar = lc.charAt(i);
            query.setParameter(1, currentChar + "%");
            List row = query.getResultList();
            Long count = (Long) row.get(0);
            results.put(String.valueOf(currentChar), count);
        }
        return results;
    }
    
    
    public List getUsersByLetter(char letter, int offset, int length)
    throws WebloggerException {
        Query query = strategy.getNamedQuery(
                "User.getByUserNameOrderByUserName");
        query.setParameter(1, letter + "%");
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }
    
    
    /**
     * Get count of users, enabled only
     */
    public long getUserCount() throws WebloggerException {
        long ret = 0;
        Query q = strategy.getNamedQuery("User.getCountEnabledDistinct");
        q.setParameter(1, Boolean.TRUE);
        List results = q.getResultList();
        ret =((Long)results.get(0)).longValue(); 
        
        return ret;
    }
    
    
	public User getUserByActivationCode(String activationCode) throws WebloggerException {
		if (activationCode == null)
			throw new WebloggerException("activationcode is null");
        Query q = strategy.getNamedQuery("User.getUserByActivationCode");
        q.setParameter(1, activationCode);
        try {
            return (User)q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }		
	}   
        
    
    //-------------------------------------------------------- permissions CRUD
 
    public boolean checkPermission(RollerPermission perm, User user) throws WebloggerException {
        RollerPermission existingPerm = null;
        
        // if permission a weblog permission
        if (perm instanceof WeblogPermission) {
            // if user has specified permission in weblog return true
            WeblogPermission permToCheck = (WeblogPermission)perm;
            try {
                existingPerm = getWeblogPermission(permToCheck.getWeblog(), user);
            } catch (WebloggerException ignored) {}        
        }

        if (perm instanceof GlobalPermission) {
            // if user has specified global permission return true
            existingPerm = new GlobalPermission(user);
        }
        
        if (existingPerm.hasActions(perm.getActionsAsList())) return true;
        if (existingPerm.implies(perm)) return true;
        return false;
    }

    
    public WeblogPermission getWeblogPermission(Weblog weblog, User user) throws WebloggerException {
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId&NotPending");
        q.setParameter(1, user.getUserName());
        q.setParameter(2, weblog.getHandle());
        try {
            return (WeblogPermission)q.getSingleResult();
        } catch (NoResultException ignored) {
            return null;
        }
    }

    
    public WeblogPermission setWeblogPermissionActions(WeblogPermission perm, String newActions) throws WebloggerException {
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId");
        q.setParameter(1, perm.getUser().getUserName());
        q.setParameter(2, perm.getWeblog().getHandle());
        try {
            WeblogPermission permToUpdate = (WeblogPermission)q.getSingleResult();
            permToUpdate.setActions(newActions);
            strategy.store(permToUpdate);
            return permToUpdate;
            
        } catch (NoResultException ignored) {
            return null;
        }
    }

    
    public void grantWeblogPermission(WeblogPermission perm) throws WebloggerException {
        
        // first, see if user already has a permission for the specified object
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId");
        q.setParameter(1, perm.getUserName());
        q.setParameter(2, perm.getObjectId());
        WeblogPermission existingPerm = null;
        try {
            existingPerm = (WeblogPermission)q.getSingleResult();
        } catch (NoResultException ignored) {}
        
        // permission already exists, so add any actions specified in perm argument
        if (existingPerm != null) {
            existingPerm.addActions(perm);
            this.strategy.store(existingPerm);
            return;            
        } else {
            // it's a new permission, so store it
            this.strategy.store(perm);
        }
    }
    
    
    public void confirmWeblogPermission(WeblogPermission perm) throws WebloggerException {
        
        // get specified permission
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId");
        q.setParameter(1, perm.getUserName());
        q.setParameter(2, perm.getObjectId());
        WeblogPermission existingPerm = null;
        try {
            existingPerm = (WeblogPermission)q.getSingleResult();
        } catch (NoResultException ignored) {
            throw new WebloggerException("ERROR: permission not found");
        }
        // set pending to false
        existingPerm.setPending(false);
        this.strategy.store(existingPerm);
    }

    
    public void declineWeblogPermission(WeblogPermission perm) throws WebloggerException {
        
        // get specified permission
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId");
        q.setParameter(1, perm.getUserName());
        q.setParameter(2, perm.getObjectId());
        WeblogPermission existingPerm = null;
        try {
            existingPerm = (WeblogPermission)q.getSingleResult();           
        } catch (NoResultException ignored) {
            throw new WebloggerException("ERROR: permission not found");
        }
        // remove permission
        this.strategy.remove(existingPerm);
    }

    
    public void revokeWeblogPermission(WeblogPermission perm) throws WebloggerException {
        
        // get specified permission
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId");
        q.setParameter(1, perm.getUserName());
        q.setParameter(2, perm.getObjectId());
        WeblogPermission oldperm = null;
        try {
            oldperm = (WeblogPermission)q.getSingleResult();
        } catch (NoResultException ignored) {
            throw new WebloggerException("ERROR: permission not found");
        }
        
        // remove actions specified in perm agument
        oldperm.removeActions(perm);
        
        if (oldperm.isEmpty()) {
            // no actions left in permission so remove it
            this.strategy.remove(oldperm);
        } else {
            // otherwise save it
            this.strategy.store(oldperm);
        }
    }
    
    
    public List<WeblogPermission> getWeblogPermissions(User user) throws WebloggerException { 
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName");
        q.setParameter(1, user.getUserName());
        return (List<WeblogPermission>)q.getResultList();
    }
    
    
    public List<WeblogPermission> getWeblogPermissions(Weblog weblog) throws WebloggerException {
        Query q = strategy.getNamedQuery("WeblogPermission.getByWeblogId");
        q.setParameter(1, weblog.getHandle());
        return (List<WeblogPermission>)q.getResultList();
    }
    
    
    public List<WeblogPermission> getWeblogPermissionsPending(User user) throws WebloggerException {
        Query q = strategy.getNamedQuery("WeblogPermission.getByUserName&Pending");
        q.setParameter(1, user.getUserName());
        return (List<WeblogPermission>)q.getResultList();
    }
    

    public List<WeblogPermission> getWeblogPermissionsPending(Weblog weblog) throws WebloggerException {
        Query q = strategy.getNamedQuery("WeblogPermission.getByWeblogId&Pending");
        q.setParameter(1, weblog.getHandle());
        return (List<WeblogPermission>)q.getResultList();
    }
    
    
    //-------------------------------------------------------------- role CRUD
 
    
    /**
     * Returns true if user has role specified.
     */
    public boolean hasRole(String roleName, User user) throws WebloggerException {
        Query q = strategy.getNamedQuery("UserRole.getByUserNameAndRole");
        q.setParameter(1, user.getUserName());
        q.setParameter(2, roleName);
        try {
            q.getSingleResult();
        } catch (NoResultException e) {
            return false;
        }
        return true;
    }
    
    
    /**
     * Get all of user's roles.
     */
    public List<String> getRoles(User user) throws WebloggerException { 
        Query q = strategy.getNamedQuery("UserRole.getByUserName");
        q.setParameter(1, user.getUserName());
        List roles = q.getResultList();
        List<String> roleNames = new ArrayList<String>();
        for (Iterator it = roles.iterator(); it.hasNext();) {
            UserRole userRole = (UserRole)it.next();
            roleNames.add(userRole.getRole());
        }
        return roleNames;
    }
    
    
    /**
     * Grant to user role specified by role name.
     */
    public void grantRole(String roleName, User user) throws WebloggerException {
        if (!hasRole(roleName, user)) {
            UserRole role = new UserRole(user.getUserName(), roleName);
            this.strategy.store(role);
        }
    }
    
    
    public void revokeRole(String roleName, User user) throws WebloggerException {
        Query q = strategy.getNamedQuery("UserRole.getByUserNameAndRole");
        q.setParameter(1, user.getUserName());
        q.setParameter(2, roleName);
        try {
            UserRole role = (UserRole)q.getSingleResult();
            this.strategy.remove(role);
            
        } catch (NoResultException e) {
            throw new WebloggerException("ERROR: removing role", e);
        }
    }
}


