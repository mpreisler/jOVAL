// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.oval.adapter.windows;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.common.MessageType;
import oval.schemas.definitions.core.ObjectType;
import oval.schemas.definitions.windows.WmiObject;
import oval.schemas.systemcharacteristics.core.EntityItemAnySimpleType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.StatusEnumeration;
import oval.schemas.systemcharacteristics.windows.WmiItem;
import oval.schemas.results.core.ResultEnumeration;

import org.joval.intf.plugin.IAdapter;
import org.joval.intf.plugin.IRequestContext;
import org.joval.intf.system.IBaseSession;
import org.joval.intf.windows.system.IWindowsSession;
import org.joval.intf.windows.wmi.ISWbemObject;
import org.joval.intf.windows.wmi.ISWbemObjectSet;
import org.joval.intf.windows.wmi.ISWbemProperty;
import org.joval.intf.windows.wmi.ISWbemPropertySet;
import org.joval.intf.windows.wmi.IWmiProvider;
import org.joval.os.windows.wmi.WmiException;
import org.joval.oval.Factories;
import org.joval.oval.OvalException;
import org.joval.util.JOVALMsg;

/**
 * Evaluates WmiTest OVAL tests.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class WmiAdapter implements IAdapter {
    private IWindowsSession session;
    private IWmiProvider wmi;

    // Implement IAdapter

    public Collection<Class> init(IBaseSession session) {
	Collection<Class> classes = new Vector<Class>();
	if (session instanceof IWindowsSession) {
	    this.session = (IWindowsSession)session;
	    classes.add(WmiObject.class);
	}
	return classes;
    }

    public Collection<WmiItem> getItems(ObjectType obj, IRequestContext rc) throws OvalException {
	wmi = session.getWmiProvider();
	Collection<WmiItem> items = new Vector<WmiItem>();
	items.add(getItem((WmiObject)obj));
	return items;
    }

    // Private

    private boolean hasResult(WmiItem item) {
	List<EntityItemAnySimpleType> results = item.getResult();
	switch(results.size()) {
	  case 0:
	    return false;
	  case 1:
	    return results.get(0).getStatus() == StatusEnumeration.EXISTS;
	  default:
	    return true;
	}
    }

    private WmiItem getItem(WmiObject wObj) {
	String id = wObj.getId();
	WmiItem item = Factories.sc.windows.createWmiItem();
	String ns = wObj.getNamespace().getValue().toString();
	EntityItemStringType namespaceType = Factories.sc.core.createEntityItemStringType();
	namespaceType.setValue(ns);
	item.setNamespace(namespaceType);
	String wql = wObj.getWql().getValue().toString();
	EntityItemStringType wqlType = Factories.sc.core.createEntityItemStringType();
	wqlType.setValue(wql);
	item.setWql(wqlType);
	try {
	    ISWbemObjectSet objSet = wmi.execQuery(ns, wql);
	    int size = objSet.getSize();
	    if (size == 0) {
		EntityItemAnySimpleType resultType = Factories.sc.core.createEntityItemAnySimpleType();
		resultType.setStatus(StatusEnumeration.DOES_NOT_EXIST);
		item.getResult().add(resultType);
	    } else {
		String field = getField(wql);
		for (ISWbemObject swbObj : objSet) {
		    for (ISWbemProperty prop : swbObj.getProperties()) {
			if (prop.getName().equalsIgnoreCase(field)) {
			    EntityItemAnySimpleType resultType = Factories.sc.core.createEntityItemAnySimpleType();
			    resultType.setValue(prop.getValueAsString());
			    item.getResult().add(resultType);
			    break;
			}
		    }
		}
	    }
	    if (!hasResult(item)) {
		item.setStatus(StatusEnumeration.DOES_NOT_EXIST);
	    }
	} catch (WmiException e) {
	    item.setStatus(StatusEnumeration.ERROR);
	    item.unsetResult();
	    MessageType msg = Factories.common.createMessageType();
	    msg.setLevel(MessageLevelEnumeration.INFO);
	    msg.setValue(e.getMessage());
	    item.getMessage().add(msg);

	    if (e instanceof WmiException) {
		session.getLogger().debug(JOVALMsg.ERROR_WINWMI_GENERAL, id);
		session.getLogger().debug(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    } else {
		session.getLogger().warn(JOVALMsg.ERROR_WINWMI_GENERAL, id);
		session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	}
	return item;
    }

    private String getField(String wql) {
	wql = wql.toUpperCase();
	StringTokenizer tok = new StringTokenizer(wql);
	while(tok.hasMoreTokens()) {
	    String token = tok.nextToken();
	    if (token.equals("SELECT")) {
		if (tok.hasMoreTokens()) {
		    return tok.nextToken();
		}
	    }
	}
	return null;
    }
}
