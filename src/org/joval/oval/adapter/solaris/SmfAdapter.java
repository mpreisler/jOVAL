// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.oval.adapter.solaris;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import oval.schemas.common.MessageType;
import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.definitions.core.ObjectType;
import oval.schemas.definitions.solaris.SmfObject;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.FlagEnumeration;
import oval.schemas.systemcharacteristics.core.StatusEnumeration;
import oval.schemas.systemcharacteristics.solaris.EntityItemSmfProtocolType;
import oval.schemas.systemcharacteristics.solaris.EntityItemSmfServiceStateType;
import oval.schemas.systemcharacteristics.solaris.SmfItem;
import oval.schemas.results.core.ResultEnumeration;

import org.joval.intf.plugin.IAdapter;
import org.joval.intf.plugin.IRequestContext;
import org.joval.intf.system.IBaseSession;
import org.joval.intf.unix.system.IUnixSession;
import org.joval.oval.CollectException;
import org.joval.oval.Factories;
import org.joval.util.JOVALMsg;
import org.joval.util.SafeCLI;
import org.joval.util.StringTools;

/**
 * Evaluates the Solaris SMF OVAL tests.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class SmfAdapter implements IAdapter {
    private IUnixSession session;
    private Hashtable<String, SmfItem> serviceMap = null;

    // Implement IAdapter

    public Collection<Class> init(IBaseSession session) {
	Collection<Class> classes = new Vector<Class>();
	if (session instanceof IUnixSession) {
	    this.session = (IUnixSession)session;
	    serviceMap = new Hashtable<String, SmfItem>();
	    classes.add(SmfObject.class);
	}
	return classes;
    }

    public Collection<SmfItem> getItems(ObjectType obj, IRequestContext rc) throws CollectException {
	SmfObject sObj = (SmfObject)obj;
	Collection<SmfItem> items = new Vector<SmfItem>();

	switch(sObj.getFmri().getOperation()) {
	  case EQUALS:
	    try {
		SmfItem item = getItem((String)sObj.getFmri().getValue());
		if (item != null) {
		    items.add(item);
		}
	    } catch (Exception e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(e.getMessage());
		rc.addMessage(msg);
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	    break;

	  case NOT_EQUAL: {
	    loadFullServiceMap();
	    for (String fmri : serviceMap.keySet()) {
		if (!fmri.equals((String)sObj.getFmri().getValue())) {
		    items.add(serviceMap.get(fmri));
		}
	    }
	    break;
	  }

	  case PATTERN_MATCH: {
	    loadFullServiceMap();
	    try {
		Pattern p = Pattern.compile((String)sObj.getFmri().getValue());
		for (String fmri : serviceMap.keySet()) {
		    if (p.matcher(fmri).find()) {
			items.add(serviceMap.get(fmri));
		    }
		}
	    } catch (PatternSyntaxException e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(JOVALMsg.getMessage(JOVALMsg.ERROR_PATTERN, e.getMessage()));
		rc.addMessage(msg);
		session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	    break;
	  }

	  default: {
	    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, sObj.getFmri().getOperation());
	    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
	  }
	}
	return items;
    }

    // Internal

    private boolean loaded = false;
    private void loadFullServiceMap() {
	if (loaded) return;

	try {
	    session.getLogger().trace(JOVALMsg.STATUS_SMF);
	    List<String> services = new Vector<String>();
	    for (String line : SafeCLI.multiLine("/usr/bin/svcs -o fmri", session, IUnixSession.Timeout.M)) {
		if (line.startsWith("FMRI")) {
		    continue;
		}
		String fmri = getFullFmri(line.trim());
		if (fmri == null) {
		    session.getLogger().warn(JOVALMsg.ERROR_FMRI, line);
		} else {
		    services.add(fmri);
		}
	    }
	    for (String service : services) {
		try {
		    SmfItem item = getItem(service);
		    serviceMap.put((String)item.getFmri().getValue(), item);
		} catch (Exception e) {
		    session.getLogger().warn(JOVALMsg.ERROR_SMF, service);
		    session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		}
	    }
	    loaded = true;
	} catch (Exception e) {
	    session.getLogger().error(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	}
    }

    private static final String FMRI		= "fmri";
    private static final String NAME		= "name";
    private static final String ENABLED		= "enabled";
    private static final String STATE		= "state";
    private static final String NEXT_STATE	= "next_state";
    private static final String STATE_TIME	= "state_time";
    private static final String RESTARTER	= "restarter";
    private static final String DEPENDENCY	= "dependency";

    private static final String START_EXEC_PROP	= "start/exec";
    private static final String INETD_USER_PROP	= "inetd_start/user";
    private static final String INETD_EXEC_PROP	= "inetd_start/exec";

    private SmfItem getItem(String fmri) throws Exception {
	SmfItem item = serviceMap.get(fmri);
	if (item != null) {
	    return item;
	}

	session.getLogger().debug(JOVALMsg.STATUS_SMF_SERVICE, fmri);
	item = Factories.sc.solaris.createSmfItem();
	boolean found = false;
	for (String line : SafeCLI.multiLine("/usr/bin/svcs -l " + fmri, session, IUnixSession.Timeout.S)) {
	    line = line.trim();
	    if (line.length() == 0) {
		break;
	    } else if (line.startsWith(FMRI)) {
		found = true;

		EntityItemStringType fmriType = Factories.sc.core.createEntityItemStringType();
		String fullFmri = getFullFmri(line.substring(FMRI.length()).trim());
		fmriType.setValue(fullFmri);
		item.setFmri(fmriType);

		//
		// Name is based on the FMRI.  See:
		// http://making-security-measurable.1364806.n2.nabble.com/Solaris-10-SMF-test-request-UNCLASSIFIED-tt23753.html#a23757
		//
		EntityItemStringType nameType = Factories.sc.core.createEntityItemStringType();
		nameType.setValue(getName(fullFmri));
		item.setServiceName(nameType);
	    } else if (line.startsWith(STATE_TIME)) { // NB: this condition MUST appear before STATE
	    } else if (line.startsWith(STATE)) {
		EntityItemSmfServiceStateType type = Factories.sc.solaris.createEntityItemSmfServiceStateType();
		type.setValue(line.substring(STATE.length()).trim().toUpperCase());
		item.setServiceState(type);
	    }
	}

	if (found) {
	    //
	    // If the service was found, then we can retrieve some information using svcprop
	    //
	    item.setStatus(StatusEnumeration.EXISTS);
	    boolean inetd = false;
	    for (String line : SafeCLI.multiLine("/usr/bin/svcprop " + fmri, session, IUnixSession.Timeout.S)) {
		if (line.startsWith(START_EXEC_PROP)) {
		    setExecAndArgs(item, line.substring(START_EXEC_PROP.length()).trim());
		} else if (line.startsWith(INETD_USER_PROP)) {
		    inetd = true;
		    String suspects = line.substring(INETD_USER_PROP.length()).trim();
		    List<String> list = StringTools.toList(StringTools.tokenize(suspects, " "));
		    if (list.size() > 0) {
			String user = list.get(list.size() - 1);
			EntityItemStringType type = Factories.sc.core.createEntityItemStringType();
			type.setValue(user);
			item.setExecAsUser(type);
		    }
		} else if (line.startsWith(INETD_EXEC_PROP)) {
		    inetd = true;
		    setExecAndArgs(item, line.substring(INETD_EXEC_PROP.length()).trim());
		}
	    }

	    //
	    // If this is an inetd-initiated service, we can get protocol information using inetadm
	    //
	    if (inetd) {
		for (String line : SafeCLI.multiLine("/usr/sbin/inetadm -l " + fmri, session, IUnixSession.Timeout.S)) {
		    if (line.trim().startsWith("proto=")) {
			String protocol = line.trim().substring(6);
			if (protocol.startsWith("\"") && protocol.endsWith("\"")) {
			    protocol = protocol.substring(1, protocol.length() - 1);
			    EntityItemSmfProtocolType type = Factories.sc.solaris.createEntityItemSmfProtocolType();
			    type.setValue(protocol);
			    item.setProtocol(type);
			}
		    }
		}
	    }
	} else {
	    EntityItemStringType fmriType = Factories.sc.core.createEntityItemStringType();
	    fmriType.setValue(fmri);
	    item.setFmri(fmriType);
	    item.setStatus(StatusEnumeration.DOES_NOT_EXIST);
	}

	return item;
    }

    /**
     * Given a property line starting after start/exec or inetd_start/exec, set the server_executable and server_arguments.
     */
    private void setExecAndArgs(SmfItem item, String argv) {
	String unescaped = argv.replace("\\ ", " ");
	StringTokenizer tok = new StringTokenizer(unescaped);
	if (tok.hasMoreTokens()) {
	    String astring = tok.nextToken();
	    if (astring.equals("astring") && tok.hasMoreTokens()) {
		String executable = tok.nextToken();
		EntityItemStringType type = Factories.sc.core.createEntityItemStringType();
		type.setValue(executable);
		item.setServerExecutable(type);
	    }
	    StringBuffer arguments = new StringBuffer();
	    while(tok.hasMoreTokens()) {
		if (arguments.length() > 0) {
		    arguments.append(" ");
		}
		arguments.append(tok.nextToken());
	    }
	    if (arguments.length() > 0) {
		EntityItemStringType type = Factories.sc.core.createEntityItemStringType();
		type.setValue(arguments.toString());
		item.setServerArguements(type);
	    }
	}
    }

    /**
     * Generally, prepend "localhost".
     */
    private String getFullFmri(String fmri) {
	if (fmri.indexOf("//") == -1) {
	    int ptr = fmri.indexOf("/");
	    if (ptr == -1) {
		return null;
	    }
	    StringBuffer sb = new StringBuffer(fmri.substring(0, ptr));
	    sb.append("//localhost");
	    sb.append(fmri.substring(ptr));
	    return sb.toString();
	} else {
	    return fmri;
	}
    }

    private String getName(String fullFmri) throws IllegalArgumentException {
	int begin = fullFmri.lastIndexOf("/");
	if (begin == -1) {
	    throw new IllegalArgumentException(fullFmri);
	} else {
	    int end = fullFmri.indexOf(":", ++begin);
	    if (end == -1) {
		end = fullFmri.length();
	    }
	    return fullFmri.substring(begin, end);
	}
    }
}
