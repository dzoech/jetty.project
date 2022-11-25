//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.osgi.annotations;

import java.io.File;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;

/**
 *
 */
public class AnnotationParser extends org.eclipse.jetty.ee10.annotations.AnnotationParser
{
    private Set<URI> _alreadyParsed = ConcurrentHashMap.newKeySet();

    private ConcurrentHashMap<URI, Bundle> _uriToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, Resource> _bundleToResource = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Resource, Bundle> _resourceToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, URI> _bundleToUri = new ConcurrentHashMap<>();

    public AnnotationParser()
    {
        super();
    }
    
    public AnnotationParser(int platform)
    {
        super(platform);
    }

    /**
     * Keep track of a jetty URI Resource and its associated OSGi bundle.
     *
     *@param resourceFactory the ResourceFactory to convert bundle location
     * @param bundle the bundle to index
     * @return the resource for the bundle
     * @throws Exception if unable to create the resource reference
     */
    public Resource indexBundle(ResourceFactory resourceFactory, Bundle bundle) throws Exception
    {
        File bundleFile = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        Resource resource = resourceFactory.newResource(bundleFile.toURI());
        URI uri = resource.getURI();
        _uriToBundle.putIfAbsent(uri, bundle);
        _bundleToUri.putIfAbsent(bundle, uri);
        _bundleToResource.putIfAbsent(bundle, resource);
        _resourceToBundle.putIfAbsent(resource, bundle);
        return resource;
    }

    protected URI getURI(Bundle bundle)
    {
        return _bundleToUri.get(bundle);
    }

    protected Resource getResource(Bundle bundle)
    {
        return _bundleToResource.get(bundle);
    }

    protected Bundle getBundle(Resource resource)
    {
        return _resourceToBundle.get(resource);
    }

    public void parse(Set<? extends Handler> handlers, Bundle bundle)
        throws Exception
    {

        Resource bundleResource = _bundleToResource.get(bundle);
        if (bundleResource == null)
            return;

        if (!_alreadyParsed.add(_bundleToUri.get(bundle)))
            return;


        parse(handlers, bundleResource);
        /*
        String bundleClasspath = (String)bundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
        if (bundleClasspath == null)
        {
            bundleClasspath = ".";
        }
        //order the paths first by the number of tokens in the path second alphabetically.
        TreeSet<String> paths = new TreeSet<>(
            new Comparator<String>()
            {
                @Override
                public int compare(String o1, String o2)
                {
                    int paths1 = new StringTokenizer(o1, "/", false).countTokens();
                    int paths2 = new StringTokenizer(o2, "/", false).countTokens();
                    if (paths1 == paths2)
                    {
                        return o1.compareTo(o2);
                    }
                    return paths2 - paths1;
                }
            });
        boolean hasDotPath = false;
        StringTokenizer tokenizer = new StringTokenizer(bundleClasspath, StringUtil.DEFAULT_DELIMS, false);
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();
            if (!token.startsWith("/"))
            {
                token = "/" + token;
            }
            if (token.equals("/."))
            {
                hasDotPath = true;
            }
            else if (!FileID.isJavaArchive(token) && !token.endsWith("/"))
            {
                paths.add(token + "/");
            }
            else
            {
                paths.add(token);
            }
        }

        @SuppressWarnings("rawtypes")
        Enumeration<URL> classes = bundle.findEntries("/", "*.class", true);
        if (classes == null)
            return;

        while (classes.hasMoreElements())
        {
            URL classUrl = classes.nextElement();
            String path = classUrl.getPath();
            //remove the longest path possible:
            String name = null;
            for (String prefixPath : paths)
            {
                if (path.startsWith(prefixPath))
                {
                    name = path.substring(prefixPath.length());
                    break;
                }
            }
            if (name == null && hasDotPath)
            {
                //remove the starting '/'
                name = path.substring(1);
            }
            if (name == null)
            {
                //found some .class file in the archive that was not under one of the prefix paths
                //or the bundle classpath wasn't simply ".", so skip it
                continue;
            }

            if (!isValidClassFileName(name))
            {
                continue; //eg skip module-info.class 
            }
            
            //transform into a classname to pass to the resolver
            String shortName = StringUtil.replace(name, '/', '.').substring(0, name.length() - 6);

            addParsedClass(shortName, getResource(bundle));

            try (InputStream classInputStream = classUrl.openStream())
            {
                scanClass(handlers, getResource(bundle), classInputStream);
            }
        }
        */
    }
}
