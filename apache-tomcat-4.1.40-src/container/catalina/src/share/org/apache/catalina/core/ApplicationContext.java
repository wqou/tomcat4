/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.catalina.core;


import java.io.InputStream;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import javax.naming.NamingException;
import javax.naming.Binding;
import javax.naming.directory.DirContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.http.HttpServletRequest;
import org.apache.naming.resources.Resource;
import org.apache.naming.resources.DirContextURLStreamHandler;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.HttpRequest;
import org.apache.catalina.Logger;
import org.apache.catalina.Wrapper;
import org.apache.catalina.deploy.ApplicationParameter;
import org.apache.catalina.util.Enumerator;
import org.apache.catalina.util.RequestUtil;
import org.apache.catalina.util.ResourceSet;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.StringManager;


/**
 * Standard implementation of <code>ServletContext</code> that represents
 * a web application's execution environment.  An instance of this class is
 * associated with each instance of <code>StandardContext</code>.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 782763 $ $Date: 2009-06-08 21:14:37 +0100 (Mon, 08 Jun 2009) $
 */

public class ApplicationContext implements ServletContext {

    protected class PrivilegedGetInitParameter implements PrivilegedAction {

        private String name;
        
        PrivilegedGetInitParameter(String name){
            this.name = name;
        }
                
        public Object run(){
            return ((String) parameters.get(name));
        }
    }


    protected class PrivilegedGetInitParameterNames
        implements PrivilegedAction {

            PrivilegedGetInitParameterNames(){
        }
   
        public Object run() {
            return (new Enumerator(parameters.keySet()));
        }
    }        


    protected class PrivilegedGetNamedDispatcher
        implements PrivilegedAction {

            private Wrapper wrapper;
            private String name;

            PrivilegedGetNamedDispatcher(Wrapper wrapper, String name) {
            this.wrapper = wrapper;
            this.name = name;
        }
        public Object run() {
            return new ApplicationDispatcher(wrapper, null, null, null, name);
        }
    }


    protected class PrivilegedGetRequestDispatcher
        implements PrivilegedAction {

        private String contextPath;
        private String relativeURI;
        private String queryString;

        PrivilegedGetRequestDispatcher(String contextPath, String relativeURI,
                                       String queryString) {
            this.contextPath = contextPath;
            this.relativeURI = relativeURI;
            this.queryString = queryString;
        }

        public Object run() {
            HttpRequest request = new MappingRequest
                (context.getPath(), contextPath + relativeURI, queryString);
            /*
            HttpRequestBase request = new HttpRequestBase();
            request.setContext(context);
            request.setContextPath(context.getPath());
            request.setRequestURI(contextPath + relativeURI);
            request.setQueryString(queryString);
            */
            Wrapper wrapper = (Wrapper) context.map(request, true);
            if (wrapper == null)
                return (null);

            // Construct a RequestDispatcher to process this request
            HttpServletRequest hrequest =
                (HttpServletRequest) request.getRequest();
            return (RequestDispatcher) new ApplicationDispatcher
                (wrapper,
                 hrequest.getServletPath(),
                 hrequest.getPathInfo(),
                 hrequest.getQueryString(),
                 null);
        }

    }



    protected class PrivilegedGetResource
        implements PrivilegedExceptionAction {

        private String path;
        private String host;
        private DirContext resources;

        PrivilegedGetResource(String host, String path, DirContext resources) {
            this.host = host;
            this.path = path;
            this.resources = resources;
        }

        public Object run() throws Exception {
            return new URL("jndi", null, 0, getJNDIUri(host, path),
                           new DirContextURLStreamHandler(resources));
        }
    }


    protected class PrivilegedGetResourcePaths
        implements PrivilegedAction {

        private String path;
        private DirContext resources;

        PrivilegedGetResourcePaths(DirContext resources, String path) {
            this.resources = resources;
            this.path = path;
        }

        public Object run() {
            return (getResourcePathsInternal(resources, path));
        }

    }


    protected class PrivilegedLogMessage
        implements PrivilegedAction {

        private String message;

        PrivilegedLogMessage(String message) {
            this.message = message;
        }

        public Object run() {
            internalLog(message);
            return null;
        }

    }

    protected class PrivilegedLogException
        implements PrivilegedAction {

        private String message;
        private Exception exception;

        PrivilegedLogException(Exception exception,String message) {
            this.message = message;
            this.exception = exception;
        }

        public Object run() {
            internalLog(exception,message);
            return null;
        }

    }

    protected class PrivilegedLogThrowable
        implements PrivilegedAction {


        private String message;
        private Throwable throwable;

        PrivilegedLogThrowable(String message,Throwable throwable) {
            this.message = message;
            this.throwable = throwable;
        }

        public Object run() {
            internalLog(message,throwable);
            return null;
        }

    }


    // ----------------------------------------------------------- Constructors


    /**
     * Construct a new instance of this class, associated with the specified
     * Context instance.
     *
     * @param context The associated Context instance
     */
    public ApplicationContext(String basePath, StandardContext context) {
        super();
        this.context = context;
        this.basePath = basePath;
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The context attributes for this context.
     */
    private HashMap attributes = new HashMap();


    /**
     * List of read only attributes for this context.
     */
    private HashMap readOnlyAttributes = new HashMap();


    /**
     * The Context instance with which we are associated.
     */
    private StandardContext context = null;


    /**
     * Empty collection to serve as the basis for empty enumerations.
     * <strong>DO NOT ADD ANY ELEMENTS TO THIS COLLECTION!</strong>
     */
    private static final ArrayList empty = new ArrayList();


    /**
     * The facade around this object.
     */
    private ServletContext facade = new ApplicationContextFacade(this);


    /**
     * The merged context initialization parameters for this Context.
     */
    private HashMap parameters = null;


    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
      StringManager.getManager(Constants.Package);


    /**
     * Base path.
     */
    private String basePath = null;


    // --------------------------------------------------------- Public Methods


    /**
     * Clear all application-created attributes.
     */
    public void clearAttributes() {

        // Create list of attributes to be removed
        ArrayList list = new ArrayList();
        synchronized (attributes) {
            Iterator iter = attributes.keySet().iterator();
            while (iter.hasNext()) {
                list.add(iter.next());
            }
        }

        // Remove application originated attributes
        // (read only attributes will be left in place)
        Iterator keys = list.iterator();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            removeAttribute(key);
        }


    }


    /**
     * Return the resources object that is mapped to a specified path.
     * The path must begin with a "/" and is interpreted as relative to the
     * current context root.
     */
    public DirContext getResources() {

        return context.getResources();

    }


    /**
     * Set an attribute as read only.
     */
    public void setAttributeReadOnly(String name) {

        synchronized (attributes) {
            if (attributes.containsKey(name))
                readOnlyAttributes.put(name, name);
        }

    }


    // ------------------------------------------------- ServletContext Methods


    /**
     * Return the value of the specified context attribute, if any;
     * otherwise return <code>null</code>.
     *
     * @param name Name of the context attribute to return
     */
    public Object getAttribute(String name) {

        synchronized (attributes) {
            return (attributes.get(name));
        }

    }


    /**
     * Return an enumeration of the names of the context attributes
     * associated with this context.
     */
    public Enumeration getAttributeNames() {

        synchronized (attributes) {
            return (new Enumerator(attributes.keySet()));
        }

    }


    /**
     * Return a <code>ServletContext</code> object that corresponds to a
     * specified URI on the server.  This method allows servlets to gain
     * access to the context for various parts of the server, and as needed
     * obtain <code>RequestDispatcher</code> objects or resources from the
     * context.  The given path must be absolute (beginning with a "/"),
     * and is interpreted based on our virtual host's document root.
     * @param uri a <code>String</code> specifying the context path of a web
     * application in the container.
     */
    public ServletContext getContext(String uri) {

        // Validate the format of the specified argument
        if ((uri == null) || (!uri.startsWith("/")))
            return (null);

        // Use the host mapper to match the uri to a context
        try {
            Host host = (Host) context.getParent();
            Context child = host.map(uri);
            if (child != null) {
            	// Without crossContext, can only return the current context
                if (context.getCrossContext()) {
                	return child.getServletContext();
                } else {
                	if (context == child) {
                		return (this);
                	} else {
                		return (null);
                	}
                }
            } else {
            	return (null);
            }
        } catch (Throwable t) {
            return (null);
        }

    }


    /**
     * Return the value of the specified initialization parameter, or
     * <code>null</code> if this parameter does not exist.
     *
     * @param name Name of the initialization parameter to retrieve
     */
    public String getInitParameter(final String name) {
        mergeParameters();
        synchronized (parameters) {
            if (System.getSecurityManager() != null){
                PrivilegedGetInitParameter ip =
                    new PrivilegedGetInitParameter(name);
                return (String)AccessController.doPrivileged(ip);
            } else {
                return ((String) parameters.get(name));
            }                   
        }
    }


    /**
     * Return the names of the context's initialization parameters, or an
     * empty enumeration if the context has no initialization parameters.
     */
    public Enumeration getInitParameterNames() {
        mergeParameters();
        synchronized (parameters) {
            if (System.getSecurityManager() != null){
                PrivilegedGetInitParameterNames pn =
                    new PrivilegedGetInitParameterNames();
                return (Enumeration)AccessController.doPrivileged(pn);
            } else {
                return (new Enumerator(parameters.keySet()));
            }
        }
    }


    /**
     * Return the major version of the Java Servlet API that we implement.
     */
    public int getMajorVersion() {

        return (Constants.MAJOR_VERSION);

    }


    /**
     * Return the minor version of the Java Servlet API that we implement.
     */
    public int getMinorVersion() {

        return (Constants.MINOR_VERSION);

    }


    /**
     * Return the MIME type of the specified file, or <code>null</code> if
     * the MIME type cannot be determined.
     *
     * @param file Filename for which to identify a MIME type
     */
    public String getMimeType(String file) {

        if (file == null)
            return (null);
        int period = file.lastIndexOf(".");
        if (period < 0)
            return (null);
        String extension = file.substring(period + 1);
        if (extension.length() < 1)
            return (null);
        return (context.findMimeMapping(extension));

    }


    /**
     * Return a <code>RequestDispatcher</code> object that acts as a
     * wrapper for the named servlet.
     *
     * @param name Name of the servlet for which a dispatcher is requested
     */
    public RequestDispatcher getNamedDispatcher(String name) {

        // Validate the name argument
        if (name == null)
            return (null);

        // Create and return a corresponding request dispatcher
        Wrapper wrapper = (Wrapper) context.findChild(name);
        if (wrapper == null)
            return (null);

        ApplicationDispatcher dispatcher;
        if (System.getSecurityManager() != null){
            PrivilegedGetNamedDispatcher nd = 
                new PrivilegedGetNamedDispatcher(wrapper, name);
            dispatcher = (ApplicationDispatcher)AccessController.doPrivileged(nd);
        } else {
            dispatcher =
                new ApplicationDispatcher(wrapper, null, null, null, name);
        }

        return ((RequestDispatcher) dispatcher);
    }


    /**
     * Return the real path for a given virtual path, if possible; otherwise
     * return <code>null</code>.
     *
     * @param path The path to the desired resource
     */
    public String getRealPath(String path) {

        if (!context.isFilesystemBased())
            return null;

        File file = new File(basePath, path);
        return (file.getAbsolutePath());

    }


    /**
     * Return a <code>RequestDispatcher</code> instance that acts as a
     * wrapper for the resource at the given path.  The path must begin
     * with a "/" and is interpreted as relative to the current context root.
     *
     * @param path The path to the desired resource.
     */
    public RequestDispatcher getRequestDispatcher(String path) {

        // Validate the path argument
        if (path == null)
            return (null);
        if (!path.startsWith("/"))
            throw new IllegalArgumentException
              (sm.getString("applicationContext.requestDispatcher.iae", path));

        // Get query string
        String queryString = null;
        int question = path.indexOf('?');
        if (question >= 0) {
            queryString = path.substring(question + 1);
            path = path.substring(0, question);
        }

        path = RequestUtil.normalize(path);
        if (path == null)
            return (null);

        // Construct a "fake" request to be mapped by our Context
        String contextPath = context.getPath();
        if (contextPath == null)
            contextPath = "";
        if( System.getSecurityManager() != null ) {
            PrivilegedGetRequestDispatcher dp =
                new PrivilegedGetRequestDispatcher(contextPath,
                        path,queryString);
            return (RequestDispatcher)AccessController.doPrivileged(dp);
        }

        // The remaining code is duplicated in PrivilegedGetRequestDispatcher,
        // we need to make sure they stay in sync
        HttpRequest request = new MappingRequest
            (context.getPath(), contextPath + path, queryString);
        /*
        request.setContext(context);
        request.setContextPath(context.getPath());
        request.setRequestURI(contextPath + relativeURI);
        request.setQueryString(queryString);
        */
        Wrapper wrapper = (Wrapper) context.map(request, true);
        if (wrapper == null)
            return (null);

        // Construct a RequestDispatcher to process this request
        HttpServletRequest hrequest =
            (HttpServletRequest) request.getRequest();
        return (RequestDispatcher) new ApplicationDispatcher(wrapper,
                        hrequest.getServletPath(),
                        hrequest.getPathInfo(),
                        hrequest.getQueryString(),
                        null);

    }



    /**
     * Return the URL to the resource that is mapped to a specified path.
     * The path must begin with a "/" and is interpreted as relative to the
     * current context root.
     *
     * @param path The path to the desired resource
     *
     * @exception MalformedURLException if the path is not given
     *  in the correct form
     */
    public URL getResource(String path)
        throws MalformedURLException {

        path = RequestUtil.normalize(path);
        if (path == null)
            return (null);

        DirContext resources = context.getResources();
        if (resources != null) {
            String fullPath = context.getName() + path;
            String hostName = context.getParent().getName();
            try {
                resources.lookup(path);
                if( System.getSecurityManager() != null ) {
                    try {
                        PrivilegedGetResource dp =
                            new PrivilegedGetResource
                                (hostName, fullPath, resources);
                        return (URL)AccessController.doPrivileged(dp);
                    } catch( PrivilegedActionException pe) {
                        throw pe.getException();
                    }
                } else {
                    return new URL
                        ("jndi", null, 0, getJNDIUri(hostName, fullPath),
                         new DirContextURLStreamHandler(resources));
                }
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }
        return (null);

    }


    /**
     * Return the requested resource as an <code>InputStream</code>.  The
     * path must be specified according to the rules described under
     * <code>getResource</code>.  If no such resource can be identified,
     * return <code>null</code>.
     *
     * @param path The path to the desired resource.

     */
    public InputStream getResourceAsStream(String path) {

        path = RequestUtil.normalize(path);
        if (path == null)
            return (null);

        DirContext resources = context.getResources();
        if (resources != null) {
            try {
                Object resource = resources.lookup(path);
                if (resource instanceof Resource)
                    return (((Resource) resource).streamContent());
            } catch (Exception e) {
            }
        }
        return (null);

    }


    /**
     * Return a Set containing the resource paths of resources member of the
     * specified collection. Each path will be a String starting with
     * a "/" character. The returned set is immutable.
     *
     * @param path Collection path
     */
    public Set getResourcePaths(String path) {

        DirContext resources = context.getResources();
        if (resources != null) {
            if (System.getSecurityManager() != null) {
                PrivilegedAction dp =
                    new PrivilegedGetResourcePaths(resources, path);
                return ((Set) AccessController.doPrivileged(dp));
            } else {
                return (getResourcePathsInternal(resources, path));
            }
        }
        return (null);

    }


    /**
     * Internal implementation of getResourcesPath() logic.
     *
     * @param resources Directory context to search
     * @param path Collection path
     */
    private Set getResourcePathsInternal(DirContext resources, String path) {

        ResourceSet set = new ResourceSet();
        try {
            listCollectionPaths(set, resources, path);
        } catch (NamingException e) {
            return (null);
        }
        set.setLocked(true);
        return (set);

    }


    /**
     * Return the name and version of the servlet container.
     */
    public String getServerInfo() {

        return (ServerInfo.getServerInfo());

    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Servlet getServlet(String name) {

        return (null);

    }


    /**
     * Return the display name of this web application.
     */
    public String getServletContextName() {

        return (context.getDisplayName());

    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Enumeration getServletNames() {
        if (System.getSecurityManager() != null){
            return (Enumeration)AccessController.doPrivileged(
                new PrivilegedAction(){

                    public Object run(){
                        return (new Enumerator(empty)); 
                    }
                }
            );
        } else {
            return (new Enumerator(empty));
        }
    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Enumeration getServlets() {
        if (System.getSecurityManager() != null){
            return (Enumeration)AccessController.doPrivileged(
                new PrivilegedAction(){

                    public Object run(){
                        return (new Enumerator(empty)); 
                    }
                }
            );
        } else {
            return (new Enumerator(empty));
        }        
    }


    /**
     * Writes the specified message to a servlet log file.
     *
     * @param message Message to be written
     */
    public void log(String message) {
        if( System.getSecurityManager() != null ) {
            PrivilegedLogMessage dp =
                new PrivilegedLogMessage(message);
            AccessController.doPrivileged(dp);
        } else {
            internalLog(message);
        }
    }

    private void internalLog(String message) {

        Logger logger = context.getLogger();
        if (logger != null)
            logger.log(message);

    }


    /**
     * Writes the specified exception and message to a servlet log file.
     *
     * @param exception Exception to be reported
     * @param message Message to be written
     *
     * @deprecated As of Java Servlet API 2.1, use
     *  <code>log(String, Throwable)</code> instead
     */
    public void log(Exception exception, String message) {
        if( System.getSecurityManager() != null ) {
            PrivilegedLogException dp =
                new PrivilegedLogException(exception,message);
            AccessController.doPrivileged(dp);
        } else {
            internalLog(exception,message);
        }
    }

    private void internalLog(Exception exception, String message) {
        Logger logger = context.getLogger();
        if (logger != null)
            logger.log(exception, message);

    }


    /**
     * Writes the specified message and exception to a servlet log file.
     *
     * @param message Message to be written
     * @param throwable Exception to be reported
     */
    public void log(String message, Throwable throwable) {
        if( System.getSecurityManager() != null ) {
            PrivilegedLogThrowable dp =
                new PrivilegedLogThrowable(message,throwable);
            AccessController.doPrivileged(dp);
        } else {
            internalLog(message,throwable);
        }
    }

    private void internalLog(String message, Throwable throwable) {

        Logger logger = context.getLogger();
        if (logger != null)
            logger.log(message, throwable);

    }


    /**
     * Remove the context attribute with the specified name, if any.
     *
     * @param name Name of the context attribute to be removed
     */
    public void removeAttribute(String name) {

        Object value = null;
        boolean found = false;

        // Remove the specified attribute
        synchronized (attributes) {
            // Check for read only attribute
           if (readOnlyAttributes.containsKey(name))
                return;
            found = attributes.containsKey(name);
            if (found) {
                value = attributes.get(name);
                attributes.remove(name);
            } else {
                return;
            }
        }

        // Notify interested application event listeners
        Object listeners[] = context.getApplicationListeners();
        if ((listeners == null) || (listeners.length == 0))
            return;
        ServletContextAttributeEvent event =
          new ServletContextAttributeEvent(context.getServletContext(),
                                            name, value);
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof ServletContextAttributeListener))
                continue;
            ServletContextAttributeListener listener =
                (ServletContextAttributeListener) listeners[i];
            try {
                context.fireContainerEvent("beforeContextAttributeRemoved",
                                           listener);
                listener.attributeRemoved(event);
                context.fireContainerEvent("afterContextAttributeRemoved",
                                           listener);
            } catch (Throwable t) {
                context.fireContainerEvent("afterContextAttributeRemoved",
                                           listener);
                // FIXME - should we do anything besides log these?
                log(sm.getString("applicationContext.attributeEvent"), t);
            }
        }

    }


    /**
     * Bind the specified value with the specified context attribute name,
     * replacing any existing value for that name.
     *
     * @param name Attribute name to be bound
     * @param value New attribute value to be bound
     */
    public void setAttribute(String name, Object value) {

        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                (sm.getString("applicationContext.setAttribute.namenull"));

        // Null value is the same as removeAttribute()
        if (value == null) {
            removeAttribute(name);
            return;
        }

        Object oldValue = null;
        boolean replaced = false;

        // Add or replace the specified attribute
        synchronized (attributes) {
            // Check for read only attribute
            if (readOnlyAttributes.containsKey(name))
                return;
            oldValue = attributes.get(name);
            if (oldValue != null)
                replaced = true;
            attributes.put(name, value);
        }

        // Notify interested application event listeners
        Object listeners[] = context.getApplicationListeners();
        if ((listeners == null) || (listeners.length == 0))
            return;
        ServletContextAttributeEvent event = null;
        if (replaced)
            event =
                new ServletContextAttributeEvent(context.getServletContext(),
                                                 name, oldValue);
        else
            event =
                new ServletContextAttributeEvent(context.getServletContext(),
                                                 name, value);

        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof ServletContextAttributeListener))
                continue;
            ServletContextAttributeListener listener =
                (ServletContextAttributeListener) listeners[i];
            try {
                if (replaced) {
                    context.fireContainerEvent
                        ("beforeContextAttributeReplaced", listener);
                    listener.attributeReplaced(event);
                    context.fireContainerEvent("afterContextAttributeReplaced",
                                               listener);
                } else {
                    context.fireContainerEvent("beforeContextAttributeAdded",
                                               listener);
                    listener.attributeAdded(event);
                    context.fireContainerEvent("afterContextAttributeAdded",
                                               listener);
                }
            } catch (Throwable t) {
                if (replaced)
                    context.fireContainerEvent("afterContextAttributeReplaced",
                                               listener);
                else
                    context.fireContainerEvent("afterContextAttributeAdded",
                                               listener);
                // FIXME - should we do anything besides log these?
                log(sm.getString("applicationContext.attributeEvent"), t);
            }
        }

    }


    // -------------------------------------------------------- Package Methods


    /**
     * Return the facade associated with this ApplicationContext.
     */
    ServletContext getFacade() {

        return (this.facade);

    }


    // -------------------------------------------------------- Private Methods

    /**
     * Merge the context initialization parameters specified in the application
     * deployment descriptor with the application parameters described in the
     * server configuration, respecting the <code>override</code> property of
     * the application parameters appropriately.
     */
    private void mergeParameters() {

        if (parameters != null)
            return;
        HashMap results = new HashMap();
        String names[] = context.findParameters();
        for (int i = 0; i < names.length; i++)
            results.put(names[i], context.findParameter(names[i]));
        ApplicationParameter params[] =
            context.findApplicationParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].getOverride()) {
                if (results.get(params[i].getName()) == null)
                    results.put(params[i].getName(), params[i].getValue());
            } else {
                results.put(params[i].getName(), params[i].getValue());
            }
        }
        parameters = results;

    }


    /**
     * List resource paths (recursively), and store all of them in the given
     * Set.
     */
    private static void listCollectionPaths
        (Set set, DirContext resources, String path)
        throws NamingException {

        Enumeration childPaths = resources.listBindings(path);
        while (childPaths.hasMoreElements()) {
            Binding binding = (Binding) childPaths.nextElement();
            String name = binding.getName();
            StringBuffer childPath = new StringBuffer(path);
            if (!"/".equals(path) && !path.endsWith("/"))
                childPath.append("/");
            childPath.append(name);
            Object object = binding.getObject();
            if (object instanceof DirContext) {
                childPath.append("/");
            }
            set.add(childPath.toString());
        }

    }


    /**
     * Get full path, based on the host name and the context path.
     */
    public static String getJNDIUri(String hostName, String path) {
        if (!path.startsWith("/"))
            return "/" + hostName + "/" + path;
        else
            return "/" + hostName + path;
    }


}

