// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.oval.adapter.independent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.bind.JAXBElement;

import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.common.MessageType;
import oval.schemas.common.OperationEnumeration;
import oval.schemas.definitions.core.EntityObjectStringType;
import oval.schemas.definitions.core.ObjectType;
import oval.schemas.definitions.macos.Plist510Object;
import oval.schemas.definitions.macos.PlistObject;
import oval.schemas.systemcharacteristics.core.EntityItemAnySimpleType;
import oval.schemas.systemcharacteristics.core.EntityItemIntType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.EntityItemVersionType;
import oval.schemas.systemcharacteristics.core.FlagEnumeration;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.StatusEnumeration;

import org.joval.intf.io.IFile;
import org.joval.intf.io.IFilesystem;
import org.joval.intf.plugin.IAdapter;
import org.joval.intf.plugin.IRequestContext;
import org.joval.intf.system.ISession;
import org.joval.intf.util.ISearchable;
import org.joval.intf.windows.system.IWindowsSession;
import org.joval.oval.CollectException;
import org.joval.oval.Factories;
import org.joval.util.JOVALMsg;
import org.joval.util.Version;

/**
 * Base class for IFile-based IAdapters. Subclasses need only implement getObjectClass, getItemClass and getItems
 * methods.  The base class handles searches and caching of search results.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public abstract class BaseFileAdapter<T extends ItemType> implements IAdapter {
    protected ISession session;

    protected void init(ISession session) {
	this.session = session;
    }

    // Implement IAdapter

    public Collection<T> getItems(ObjectType obj, IRequestContext rc) throws CollectException {
	String id = obj.getId();

	//
	// Get the appropriate IFilesystem
	//
	ReflectedFileObject fObj = new ReflectedFileObject(obj);
	ReflectedFileBehaviors behaviors = null;
	if (fObj.isSetBehaviors()) {
	    behaviors = fObj.getBehaviors();
	}
	IFilesystem fs = null;
	int winView = 0;
	if (session instanceof IWindowsSession) {
	    if (behaviors != null) {
		if ("32_bit".equals(behaviors.getWindowsView())) {
		    fs = ((IWindowsSession)session).getFilesystem(IWindowsSession.View._32BIT);
		    winView = 32;
		}
	    }
	    if (winView == 0 && ((IWindowsSession)session).supports(IWindowsSession.View._64BIT)) {
		winView = 64;
	    }
	}
	if (fs == null) {
	    fs = session.getFilesystem();
	}

	Collection<T> items = new Vector<T>();
	for (String path : getPathList(fObj, rc, fs)) {
	    IFile f = null;
	    try {
		f = fs.getFile(path);
		String dirPath = null;
		//
		// DAS: if DH says don't follow links, then add a test for isLink.
		//
		boolean isDirectory = f.isDirectory();
		if (isDirectory) {
		    dirPath = path;
		} else {
		    dirPath = f.getParent();
		}
		ReflectedFileItem fItem = new ReflectedFileItem();
		if (fObj.isSetFilepath()) {
		    if (isDirectory) {
			//
			// Object is looking for files, so skip over this directory
			//
			continue;
		    } else {
			EntityItemStringType filepathType = Factories.sc.core.createEntityItemStringType();
			filepathType.setValue(path);
			EntityItemStringType pathType = Factories.sc.core.createEntityItemStringType();
			pathType.setValue(getPath(path, f));
			EntityItemStringType filenameType = Factories.sc.core.createEntityItemStringType();
			filenameType.setValue(f.getName());
			fItem.setFilepath(filepathType);
			fItem.setPath(pathType);
			fItem.setFilename(filenameType);
		    }
		} else if (fObj.isSetFilename() && fObj.getFilename().getValue() != null) {
		    if (isDirectory) {
			//
			// Object is looking for files, so skip over this directory
			//
			continue;
		    } else {
			EntityItemStringType filepathType = Factories.sc.core.createEntityItemStringType();
			filepathType.setValue(path);
			EntityItemStringType pathType = Factories.sc.core.createEntityItemStringType();
			pathType.setValue(getPath(path, f));
			EntityItemStringType filenameType = Factories.sc.core.createEntityItemStringType();
			filenameType.setValue(f.getName());
			fItem.setFilepath(filepathType);
			fItem.setPath(pathType);
			fItem.setFilename(filenameType);
		    }
		} else if (fObj.isSetPath()) {
		    if (!isDirectory && fObj.isSetFilename() && fObj.getFilename().getValue() == null) {
			//
			// If xsi:nil is set for the filename element, we only want directories...
			//
			continue;
		    }

		    EntityItemStringType pathType = Factories.sc.core.createEntityItemStringType();
		    pathType.setValue(dirPath);
		    fItem.setPath(pathType);
		    if (!isDirectory) {
			EntityItemStringType filenameType = Factories.sc.core.createEntityItemStringType();
			filenameType.setValue(f.getName());
			fItem.setFilename(filenameType);
			EntityItemStringType filepathType = Factories.sc.core.createEntityItemStringType();
			filepathType.setValue(f.getPath());
			fItem.setFilepath(filepathType);
		    }
		} else {
		    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_FILE_SPEC, obj.getClass().getName(), id);
		    throw new CollectException(msg, FlagEnumeration.ERROR);
		}

		switch(winView) {
		  case 32:
		    fItem.setWindowsView("32_bit");
		    break;
		  case 64:
		    fItem.setWindowsView("64_bit");
		    break;
		}

		items.addAll(getItems(obj, fItem.it, f, rc));
	    } catch (FileNotFoundException e) {
		// skip it
	    } catch (ClassNotFoundException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_REFLECTION, e.getMessage(), id);
	    } catch (InstantiationException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_REFLECTION, e.getMessage(), id);
	    } catch (NoSuchMethodException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_REFLECTION, e.getMessage(), id);
	    } catch (IllegalAccessException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_REFLECTION, e.getMessage(), id);
	    } catch (InvocationTargetException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_REFLECTION, e.getMessage(), id);
	    } catch (IllegalArgumentException e) {
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		session.getLogger().warn(JOVALMsg.ERROR_IO, path, e.getMessage());
	    } catch (IOException e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		if (f == null) {
		    msg.setValue(e.getMessage());
		} else {
		    msg.setValue(JOVALMsg.getMessage(JOVALMsg.ERROR_IO, path, e.getMessage()));
		}
		rc.addMessage(msg);
	    }
	}
	return items;
    }

    // Protected

    /**
     * Return the Class of the ItemTypes generated by the subclass.
     */
    protected abstract Class getItemClass();

    /**
     * Return a list of items to associate with the given ObjectType, based on information gathered from the IFile.
     *
     * @arg it the base ItemType containing filepath, path and filename information already populated
     */
    protected abstract Collection<T> getItems(ObjectType obj, ItemType it, IFile f, IRequestContext rc)
	throws IOException, CollectException;

    // Internal

    /**
     * Get a list of String paths for this object.  This accommodates searches (from pattern match operations),
     * singletons (from equals operations) and handles recursive searches specified by FileBehaviors.
     *
     * The resulting collection should only contain matches that exist.
     */
    final Collection<String> getPathList(ReflectedFileObject fObj, IRequestContext rc, IFilesystem fs) throws CollectException {
	String id = fObj.getId();
	ReflectedFileBehaviors fb = fObj.getBehaviors();
	Collection<String> list = new HashSet<String>();
	try {
	    if (fObj.isSetFilepath()) {
		//
		// Windows view is already handled, and FileBehaviors recursion is ignored in the Filepath case, so
		// all we need to do is add the discovered paths to the return list.
		//
		EntityObjectStringType filepath = fObj.getFilepath();
		String value = (String)filepath.getValue();
		OperationEnumeration op = filepath.getOperation();
		switch(op) {
		  case EQUALS:
		    try {
			if (fs.getFile((String)filepath.getValue()).isFile()) {
			    list.add(value);
			}
		    } catch (FileNotFoundException e) {
		    } catch (IOException e) {
			session.getLogger().warn(JOVALMsg.ERROR_IO, value, e.getMessage());
			session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		    }
		    break;

		  case PATTERN_MATCH:
		    for (String match : fs.search(Pattern.compile(value), ISearchable.FOLLOW_LINKS)) {
			try {
			    if ((fs.getFile(match)).isFile()) {
				list.add(match);
			    }
			} catch (FileNotFoundException e) {
			    session.getLogger().debug(JOVALMsg.ERROR_NODE_LINK, match);
			} catch (IOException e) {
			    session.getLogger().warn(JOVALMsg.ERROR_IO, match, e.getMessage());
			    session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
			}
		    }
		    break;

		  default:
		    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
		    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
		}
	    } else if (fObj.isSetPath()) {
		//
		// First, collect all possible matching paths (i.e., dirs)
		//
		Collection<String> paths = new HashSet<String>();
		EntityObjectStringType path = fObj.getPath();
		String value = (String)path.getValue();
		OperationEnumeration op = path.getOperation();
		switch(op) {
		  case EQUALS:
		    try {
			if ((fs.getFile(value)).isDirectory()) {
			    list.add(value);
			}
		    } catch (FileNotFoundException e) {
		    } catch (IOException e) {
			session.getLogger().warn(JOVALMsg.ERROR_IO, value, e.getMessage());
			session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		    }
		    if (fb != null) {
			//
			// Recursive search File Behaviors always only apply exclusively to well-defined paths, never
			// patterns.
			//
			list = getDirs(list, fb.getDepth(), fb, fs, null);
		    }
		    break;

		  case PATTERN_MATCH:
		    for (String match : fs.search(Pattern.compile(value), ISearchable.FOLLOW_LINKS)) {
			try {
			    //
			    // Filter search results for directories
			    //
			    if ((fs.getFile(match)).isDirectory()) {
				list.add(match);
			    }
			} catch (FileNotFoundException e) {
			    session.getLogger().debug(JOVALMsg.ERROR_NODE_LINK, match);
			} catch (IOException e) {
			    session.getLogger().warn(JOVALMsg.ERROR_IO, match, e.getMessage());
			    session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
			}
		    }
		    break;

		  default:
		    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
		    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
		}

		//
		// Next, for each possible directory match, look for the file(s) if specified.
		//
		if (fObj.isSetFilename()) {
		    EntityObjectStringType filename = fObj.getFilename();
		    String fname = (String)filename.getValue();
		    Collection<String> files = new Vector<String>();
		    for (String pathString : list) {
			try {
			    op = filename.getOperation();
			    switch(op) {
			      case PATTERN_MATCH: {
				IFile f = fs.getFile(pathString);
				if (f.isDirectory()) {
				    for (IFile child : f.listFiles(Pattern.compile(fname))) {
					if (child.isFile()) {
					    files.add(child.getPath());
					}
				    }
				}
				break;
			      }
 
			      case EQUALS: {
				IFile f = fs.getFile(pathString).getChild(fname);
				if (f.exists()) {
				    files.add(f.getPath());
				}
				break;
			      }

			      case NOT_EQUAL: {
				IFile f = fs.getFile(pathString);
				if (f.isDirectory()) {
				    for (IFile child : f.listFiles(Pattern.compile(fname))) {
					if (child.isFile()) {
					    files.add(child.getPath());
					}
				    }
				}
				break;
			      }
 
			      default:
				String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
				throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
			    }
			} catch (FileNotFoundException e) {
			} catch (IllegalArgumentException e) {
			    session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
			} catch (IOException e) {
			    MessageType msg = Factories.common.createMessageType();
			    msg.setLevel(MessageLevelEnumeration.ERROR);
			    msg.setValue(e.getMessage());
			    rc.addMessage(msg);
			}
		    }
		    list = files;
		}
	    } else if (isPlistObject(fObj.obj)) {
		list.add(getPlistPath(fObj.obj));
	    } else {
		//
		// This has probably happened because one or more variables resolves to nothing.
		//
		session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_BAD_FILE_OBJECT, id));
	    }
	} catch (PatternSyntaxException e) {
       	    session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	}
	return list;
    }

    /**
     * Is the object a Plist type?
     */
    private boolean isPlistObject(ObjectType obj) {
	return obj instanceof PlistObject || obj instanceof Plist510Object;
    }

    /**
     * Get the path of the plist file based on the app_id.
     */
    private String getPlistPath(ObjectType obj) throws CollectException {
	EntityObjectStringType appIdType = null;
	if (obj instanceof PlistObject) {
	    appIdType = ((PlistObject)obj).getAppId();
	} else if (obj instanceof Plist510Object) {
	    appIdType = ((Plist510Object)obj).getAppId();
	}
	if (appIdType != null) {
	    Collection<String> paths = new Vector<String>();

	    StringBuffer sb = new StringBuffer(session.getEnvironment().getenv("HOME"));
	    sb.append("/Library/Preferences/");
	    if (appIdType.isSetValue()) {
		return sb.append((String)appIdType.getValue()).append(".plist").toString();
	    }
	}
	String message = JOVALMsg.getMessage(JOVALMsg.ERROR_BAD_PLIST_OBJECT, obj.getId());
	throw new CollectException(message, FlagEnumeration.ERROR);
    }

    /**
     * Finds directories recursively based on FileBahaviors.
     */
    private Collection<String> getDirs(Collection<String> list, int depth, ReflectedFileBehaviors behaviors,
				       IFilesystem fs, Stack<String> ancestors) {

	if ("none".equals(behaviors.getRecurseDirection()) || depth == 0) {
	    return list;
	} else {
	    Collection<String> results = new HashSet<String>();
	    for (String path : list) {
		if (ancestors == null) {
		    ancestors = new Stack<String>();
		}
		session.getLogger().trace(JOVALMsg.STATUS_FS_RECURSE, path);
		try {
		    String recurse = behaviors.getRecurse();
		    String recurseFs = behaviors.getRecurseFileSystem();
		    IFile f = fs.getFile(path);
		    if (f == null) {
			// skip permission denied (or other access error)
		    } else if (!f.exists()) {
			// skip non-existent files
		    } else if (recurse != null && recurse.indexOf("symlinks") == -1 && f.isLink()) {
			// skip the symlink
		    } else if (recurse != null && recurse.indexOf("directories") == -1 && f.isDirectory()) {
			// skip the directory
		    } else if (f.isLink() && ancestors.contains(f.getCanonicalPath())) {
			// Skip the loop back to an ancestor!
			// NB: This point is only reached if symlinks are being followed
			session.getLogger().warn(JOVALMsg.STATUS_FS_LOOP, f.getPath());
		    } else if (f.isDirectory()) {
			results.add(path);
			ancestors.push(f.getCanonicalPath());
			if ("up".equals(behaviors.getRecurseDirection())) {
			    String parent = f.getParent();
			    if (parent.equals(f.getPath())) {
				// reached root
			    } else if (checkRecurse(recurseFs, f, fs.getFile(parent))) {
				Collection<String> c = new HashSet<String>();
				c.add(parent);
				results.addAll(getDirs(c, --depth, behaviors, fs, cloneStack(ancestors)));
			    }
			} else { // recurse down
			    Collection<String> c = new HashSet<String>();
			    for (IFile child : f.listFiles()) {
				if (checkRecurse(recurseFs, f, child)) {
				    c.add(child.getPath());
				}
			    }
			    results.addAll(getDirs(c, --depth, behaviors, fs, cloneStack(ancestors)));
			}
		    }
		} catch (UnsupportedOperationException e) {	
		    // ignore -- not a directory
		} catch (FileNotFoundException e) {
		    // link is not a link to a directory
		} catch (IOException e) {
		    session.getLogger().warn(JOVALMsg.ERROR_IO, path, e.getMessage());
		    session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		}
	    }
	    return results;
	}
    }

    private boolean warnedRecurse = false;

    private boolean checkRecurse(String recurseFs, IFile origin, IFile destination) throws IOException {
	if (!destination.isDirectory()) {
	    return false;
	} else if (!origin.isLink() && !destination.isLink()) {
	    return true;
	} else if ("defined".equals(recurseFs)) {
	    if (!origin.getFSName().equals(destination.getFSName())) {
		session.getLogger().info(JOVALMsg.STATUS_FS_SKIP, destination.getPath(), recurseFs);
		return false;
	    }
	} else if ("local".equals(recurseFs)) {
	    try {
		if (!session.getFilesystem().isLocalPath(destination.getPath())) {
		    session.getLogger().info(JOVALMsg.STATUS_FS_SKIP, destination.getPath(), recurseFs);
		    return false;
		}
	    } catch (UnsupportedOperationException e) {
		if (!warnedRecurse) {
		    session.getLogger().error(JOVALMsg.ERROR_UNSUPPORTED_BEHAVIOR, recurseFs);
		    warnedRecurse = true;
		}
	    }
	}
	return true;
    }

    private String getPath(String path, IFile f) {
	String name = f.getName();
	int len = path.length();
	if (len > 1) {
	    return path.substring(0, len - name.length() - 1);
	} else {
	    return path;
	}
    }

    private Stack<String> cloneStack(Stack<String> source) {
	Stack<String> newStack = new Stack<String>();
	newStack.addAll(source);
	return newStack;
    }

    /**
     * A reflection proxy for:
     *     oval.schemas.definitions.independent.Textfilecontent54Object
     *     oval.schemas.definitions.independent.TextfilecontentObject
     *     oval.schemas.definitions.unix.FileObject
     *     oval.schemas.definitions.windows.FileObject
     */
    class ReflectedFileObject {
	ObjectType obj;
	String id = null;
	boolean filenameNil = false;
	EntityObjectStringType filepath = null, path = null, filename = null;
	ReflectedFileBehaviors behaviors = null;

	ReflectedFileObject(ObjectType obj) {
	    try {
		Method getId = obj.getClass().getMethod("getId");
		Object o = getId.invoke(obj);
		if (o != null) {
		    id = (String)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }

	    try {
		Method getFilepath = obj.getClass().getMethod("getFilepath");
		Object o = getFilepath.invoke(obj);
		if (o != null) {
		    filepath = (EntityObjectStringType)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }

	    try {
		Method getFilename = obj.getClass().getMethod("getFilename");
		Object o = getFilename.invoke(obj);
		if (o != null) {
		    if (o instanceof JAXBElement) {
			JAXBElement j = (JAXBElement)o;
			filenameNil = j.isNil();
			o = j.getValue();
		    }
		    filename = (EntityObjectStringType)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }

	    try {
		Method getPath = obj.getClass().getMethod("getPath");
		Object o = getPath.invoke(obj);
		if (o != null) {
		    path = (EntityObjectStringType)o;
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }

	    try {
		Method getBehaviors = obj.getClass().getMethod("getBehaviors");
		Object o = getBehaviors.invoke(obj);
		if (o != null) {
		    behaviors = new ReflectedFileBehaviors(o);
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    }
	}

	String getId() {
	    return id;
	}

	boolean isSetFilepath() {
	    return filepath != null;
	}

	EntityObjectStringType getFilepath() {
	    return filepath;
	}

	boolean isFilenameNil() {
	    return filenameNil;
	}

	boolean isSetFilename() {
	    return filename != null;
	}

	EntityObjectStringType getFilename() {
	    return filename;
	}

	boolean isSetPath() {
	    return path != null;
	}

	EntityObjectStringType getPath() {
	    return path;
	}

	boolean isSetBehaviors() {
	    return behaviors != null;
	}

	ReflectedFileBehaviors getBehaviors() {
	    return behaviors;
	}
    }

    /**
     * A reflection proxy for:
     *     oval.schemas.definitions.independent.FileBehaviors
     *     oval.schemas.definitions.unix.FileBehaviors
     *     oval.schemas.definitions.windows.FileBehaviors
     */
    class ReflectedFileBehaviors {
	BigInteger maxDepth = new BigInteger("-1");
	String recurseDirection = "none";
	String recurse = "symlinks and directories";
	String recurseFS = "all";
	String windowsView = "64_bit";

	ReflectedFileBehaviors(Object obj)
		throws NoSuchMethodException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (obj != null) {
		Method getMaxDepth = obj.getClass().getMethod("getMaxDepth");
		maxDepth = (BigInteger)getMaxDepth.invoke(obj);

		Method getRecurseDirection = obj.getClass().getMethod("getRecurseDirection");
		recurseDirection = (String)getRecurseDirection.invoke(obj);

		try {
		    //
		    // Not applicable to Unix FileBehaviors
		    //
		    Method getWindowsView = obj.getClass().getMethod("getWindowsView");
		    windowsView = (String)getWindowsView.invoke(obj);
		} catch (NoSuchMethodException e) {
		    recurse = null;
		}

		try {
		    //
		    // Not applicable to Windows FileBehaviors
		    //
		    Method getRecurse = obj.getClass().getMethod("getRecurse");
		    recurse = (String)getRecurse.invoke(obj);
		} catch (NoSuchMethodException e) {
		    recurse = null;
		}

		Method getRecurseFileSystem = obj.getClass().getMethod("getRecurseFileSystem");
		recurseFS = (String)getRecurseFileSystem.invoke(obj);
	    }
	    if ("none".equals(recurseDirection)) {
		maxDepth = BigInteger.ZERO;
	    }
	}

	String getRecurse() {
	    return recurse;
	}

	String getRecurseDirection() {
	    return recurseDirection;
	}

	int getDepth() {
	    return Integer.parseInt(maxDepth.toString());
	}

	String getRecurseFileSystem() {
	    return recurseFS;
	}

	String getWindowsView() {
	    return windowsView;
	}
    }

    /**
     * A reflection proxy for:
     *     oval.schemas.systemcharacteristics.independent.TextfilecontentItem
     *     oval.schemas.systemcharacteristics.unix.FileItem
     *     oval.schemas.systemcharacteristics.windows.FileItem
     */
    class ReflectedFileItem {
	ItemType it;
	Method setFilepath, setFilename, setPath, setStatus, setWindowsView, wrapFilename=null;
	Object factory;

	ReflectedFileItem() throws ClassNotFoundException, InstantiationException, NoSuchMethodException,
		IllegalAccessException, InvocationTargetException {

	    Class clazz = getItemClass();
	    String className = clazz.getName();
	    String packageName = clazz.getPackage().getName();
	    String unqualClassName = className.substring(packageName.length() + 1);
	    Class<?> factoryClass = Class.forName(packageName + ".ObjectFactory");
	    factory = factoryClass.newInstance();
	    Method createType = factoryClass.getMethod("create" + unqualClassName);
	    it = (ItemType)createType.invoke(factory);

	    Method[] methods = it.getClass().getMethods();
	    for (int i=0; i < methods.length; i++) {
		String name = methods[i].getName();
		if ("setFilepath".equals(name)) {
		    setFilepath = methods[i];
		} else if ("setFilename".equals(name)) {
		    setFilename = methods[i];
		    String filenameClassName = setFilename.getParameterTypes()[0].getName();
		    if (filenameClassName.equals(JAXBElement.class.getName())) {
			String methodName = "create" + unqualClassName + "Filename";
			wrapFilename = factoryClass.getMethod(methodName, EntityItemStringType.class);
		    }
		} else if ("setPath".equals(name)) {
		    setPath = methods[i];
		} else if ("setStatus".equals(name)) {
		    setStatus = methods[i];
		} else if ("setWindowsView".equals(name)) {
		    setWindowsView = methods[i];
		}
	    }
	}

	void setWindowsView(String view) {
	    try {
		if (setWindowsView != null) {
		    Class[] types = setWindowsView.getParameterTypes();
		    if (types.length == 1) {
			Class type = types[0];
			Object instance = Class.forName(type.getName());
			@SuppressWarnings("unchecked")
			Method setValue = type.getMethod("setValue", Object.class);
			setValue.invoke(instance, view);
			setWindowsView.invoke(it, instance);
		    }
		}
	    } catch (NoSuchMethodException e) {
	    } catch (IllegalAccessException e) {
	    } catch (IllegalArgumentException e) {
	    } catch (InvocationTargetException e) {
	    } catch (ClassNotFoundException e) {
	    }
	}

	void setFilename(EntityItemStringType filename)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setFilename != null) {
		if (wrapFilename == null) {
		    setFilename.invoke(it, filename);
		} else {
		    setFilename.invoke(it, wrapFilename.invoke(factory, filename));
		}
	    }
	}

	void setFilepath(EntityItemStringType filepath)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setFilepath != null) {
		setFilepath.invoke(it, filepath);
	    }
	}

	void setPath(EntityItemStringType path)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setPath != null) {
		setPath.invoke(it, path);
	    }
	}

	void setStatus(StatusEnumeration status)
		throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	    if (setStatus != null) {
		setStatus.invoke(it, status);
	    }
	}
    }
}
