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

package org.apache.roller.weblogger.pojos;


/**
 * A Theme specific implementation of a Template.
 * 
 * A ThemeTemplate represents a template which is part of a Theme.
 */
public interface ThemeTemplate extends Template {
    
    String ACTION_WEBLOG = "weblog";
    String ACTION_PERMALINK = "permalink";
    String ACTION_SEARCH = "search";
    String ACTION_TAGSINDEX = "tagsIndex";
    String ACTION_CUSTOM = "custom";
    
    // the full list of supported special actions, which purposely does not
    // contain an entry for the 'custom' action
    String[] ACTIONS = {
        ACTION_WEBLOG, 
        ACTION_PERMALINK, 
        ACTION_SEARCH, 
        ACTION_TAGSINDEX
    };
    
    
    /**
     * The action this template is defined for.
     */
    String getAction();
    
    
    /**
     * The contents or body of the Template.
     */
    String getContents();

    /**
     * set content. This will be used by template code objects to store different
     * types of template contents
     */
    void setContents(String contents);

    /**
     * The url link value for this Template.  If this template is not
     * private this is the url that it can be accessed at.
     */
    String getLink();
    
    
    /**
     * Is the Template hidden?  A hidden template cannot be accessed directly.
     */
    boolean isHidden();
    
    
    /**
     * Is the Template to be included in the navbar?
     */
    boolean isNavbar();
    
    
    /**
     * The name of the decorator template to apply.
     */
    String getDecoratorName();
    
    
    /**
     * The decorator Template to apply.  This returns null if no decorator
     * should be applied.
     */
    ThemeTemplate getDecorator();

}
