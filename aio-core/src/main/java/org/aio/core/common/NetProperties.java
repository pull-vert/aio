/*
 * Copyright (c) 2018-2019 AIO's author : Fred Montariol
 *
 * Use of this source code is governed by the GNU General Public License v2.0,
 * and is subject to the "Classpath" exception as provided in the LICENSE
 * file that accompanied this code.
 *
 *
 * This file is a fork of OpenJDK sun.net.NetProperties
 *
 * In initial Copyright below, LICENCE file refers to OpendJDK licence, a copy
 * is provided in the OPENJDK_LICENCE file that accompanied this code.
 *
 * INITIAL COPYRIGHT NOTICES AND FILE HEADER
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.aio.core.common;

import java.io.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Properties;

/*
 * This class allows for centralized access to Networking properties.
 * Default values are loaded from the file jre/lib/net.properties
 *
 *
 * @author Jean-Christophe Collet
 *
 */

public class NetProperties {
    private static Properties props = new Properties();
    static {
        AccessController.doPrivileged(
                (PrivilegedAction<Void>) () -> {
                    loadDefaultProperties();
                    return null;
                });
    }

    private NetProperties() { }


    /*
     * Loads the default networking system properties
     * the file is in jre/lib/net.properties
     */
    private static void loadDefaultProperties() {
        String fname = System.getProperty("java.home");
        if (fname == null) {
            throw new Error("Can't find java.home ??");
        }
        try {
            File f = new File(fname, "conf");
            f = new File(f, "net.properties");
            fname = f.getCanonicalPath();
            InputStream in = new FileInputStream(fname);
            BufferedInputStream bin = new BufferedInputStream(in);
            props.load(bin);
            bin.close();
        } catch (Exception e) {
            // Do nothing. We couldn't find or access the file
            // so we won't have default properties...
        }
    }

    /**
     * Get a networking system property. If no system property was defined
     * returns the default value, if it exists, otherwise returns
     * <code>null</code>.
     * @param      key  the property name.
     * @throws  SecurityException  if a security manager exists and its
     *          <code>checkPropertiesAccess</code> method doesn't allow access
     *          to the system properties.
     * @return the <code>String</code> value for the property,
     *         or <code>null</code>
     */
    public static String get(String key) {
        String def = props.getProperty(key);
        try {
            return System.getProperty(key, def);
        } catch (IllegalArgumentException | NullPointerException e) {
        }
        return null;
    }

    /**
     * Get an Integer networking system property. If no system property was
     * defined returns the default value, if it exists, otherwise returns
     * <code>null</code>.
     * @param   key     the property name.
     * @param   defval  the default value to use if the property is not found
     * @throws  SecurityException  if a security manager exists and its
     *          <code>checkPropertiesAccess</code> method doesn't allow access
     *          to the system properties.
     * @return the <code>Integer</code> value for the property,
     *         or <code>null</code>
     */
    static Integer getInteger(String key, int defval) {
        String val = null;

        try {
            val = System.getProperty(key, props.getProperty(key));
        } catch (IllegalArgumentException | NullPointerException e) {
        }

        if (val != null) {
            try {
                return Integer.decode(val);
            } catch (NumberFormatException ex) {
            }
        }
        return defval;
    }

    /**
     * Get a Boolean networking system property. If no system property was
     * defined returns the default value, if it exists, otherwise returns
     * <code>null</code>.
     * @param   key     the property name.
     * @throws  SecurityException  if a security manager exists and its
     *          <code>checkPropertiesAccess</code> method doesn't allow access
     *          to the system properties.
     * @return the <code>Boolean</code> value for the property,
     *         or <code>null</code>
     */
    public static Boolean getBoolean(String key) {
        String val = null;

        try {
            val = System.getProperty(key, props.getProperty(key));
        } catch (IllegalArgumentException | NullPointerException e) {
        }

        if (val != null) {
            try {
                return Boolean.valueOf(val);
            } catch (NumberFormatException ex) {
            }
        }
        return null;
    }

}
