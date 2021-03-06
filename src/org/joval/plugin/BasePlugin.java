// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.plugin;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.PropertyResourceBundle;

import org.slf4j.cal10n.LocLogger;

import org.joval.intf.plugin.IPlugin;
import org.joval.intf.system.IBaseSession;
import org.joval.util.JOVALMsg;

/**
 * The abstract base class for all functional jovaldi plug-ins.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public abstract class BasePlugin implements IPlugin {
    protected LocLogger logger;
    protected IBaseSession session;
    PropertyResourceBundle resources;
    File dir;

    /**
     * Loads localized resources for the plugin using the default basename, "plugin".
     */
    BasePlugin() {
	this("plugin");
    }

    /**
     * Loads localized resources for the plugin using a customized basename.
     */
    BasePlugin(String basename) {
	logger = JOVALMsg.getLogger();
	try {
	    ClassLoader cl = BasePlugin.class.getClassLoader();
	    Locale locale = Locale.getDefault();
	    URL url = cl.getResource(basename + ".resources_" + locale.toString() + ".properties");
	    if (url == null) {
		url = cl.getResource(basename + ".resources_" + locale.getLanguage() + ".properties");
	    }
	    if (url == null) {
		url = cl.getResource(basename + ".resources.properties");
	    }
	    resources = new PropertyResourceBundle(url.openStream());
	} catch (IOException e) {
	    JOVALMsg.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	}
    }

    // Implement ILoggable

    public LocLogger getLogger() {
	return logger;
    }

    public void setLogger(LocLogger logger) {
	this.logger = logger;
	session.setLogger(logger);
    }

    // Implement IPlugin

    public String getProperty(String key) {
	return resources.getString(key);
    }

    public String getMessage(String key, Object... arguments) {
	return MessageFormat.format(resources.getString(key), arguments);
    }

    public void setDataDirectory(File dir) throws IOException {
	this.dir = dir;
    }

    /**
     * Subclasses should override this method.
     */
    public void configure(Properties props) throws Exception {
	if (props == null) {
	    throw new Exception(getMessage("err.configMissing", DEFAULT_FILE));
	}
    }

    public IBaseSession getSession() {
	return session;
    }
}
