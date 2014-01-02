/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.jaxrs.model.wadl;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Path;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.jaxrs.model.OperationResourceInfo;
import org.apache.cxf.jaxrs.utils.ResourceUtils;

public class JavaDocProvider implements DocumentationProvider {
    public static final double JAVA_VERSION = getVersion();
    public static final double JAVA_VERSION_16 = 1.6D;

    private ClassLoader javaDocLoader;
    private ConcurrentHashMap<String, ClassDocs> docs = new ConcurrentHashMap<String, ClassDocs>();
    private double javaDocsBuiltByVersion = JAVA_VERSION;
    
    public JavaDocProvider(URL javaDocUrl) {
        if (javaDocUrl == null) {
            throw new IllegalArgumentException("URL is null");
        }
        javaDocLoader = new URLClassLoader(new URL[]{javaDocUrl});
    }
    
    public JavaDocProvider(String path) throws Exception {
        this(BusFactory.getDefaultBus(), path);
    }
    
    public JavaDocProvider(Bus bus, String path) throws Exception {
        this(ResourceUtils.getResourceURL(path, bus));
    }
    
    private static double getVersion() {
        String version = System.getProperty("java.version");
        try {
            return Double.parseDouble(version.substring(0, 3));    
        } catch (Exception ex) {
            return JAVA_VERSION_16;
        }
    }
    
    public String getClassDoc(ClassResourceInfo cri) {
        try {
            ClassDocs doc = getClassDocInternal(cri);
            if (doc == null) {
                return null;
            }
            return doc.getClassInfo();
        } catch (Exception ex) {
            // ignore    
        }
        return null;
    }
    
    public String getMethodDoc(OperationResourceInfo ori) {
        try {
            MethodDocs doc = getOperationDocInternal(ori);
            if (doc == null) {
                return null;
            }
            return doc.getMethodInfo();
        } catch (Exception ex) {
            // ignore
        }
        return null;
    }
    
    public String getMethodResponseDoc(OperationResourceInfo ori) {
        try {
            MethodDocs doc = getOperationDocInternal(ori);
            if (doc == null) {
                return null;
            }
            return doc.getResponseInfo();
        } catch (Exception ex) {
            // ignore    
        }
        return null;
    }
    
    public String getMethodParameterDoc(OperationResourceInfo ori, int paramIndex) {
        try {
            MethodDocs doc = getOperationDocInternal(ori);
            if (doc == null) {
                return null;
            }
            List<String> params = doc.getParamInfo();
            if (paramIndex < params.size()) {
                return params.get(paramIndex);
            } else {
                return null;
            }
        } catch (Exception ex) {
            // ignore    
        }
        return null;
    }
    
    private Class<?> getPathAnnotatedClass(Class<?> cls) {
        if (cls.getAnnotation(Path.class) != null) { 
            return cls;
        }
        if (cls.getSuperclass().getAnnotation(Path.class) != null) {
            return cls.getSuperclass();
        }
        for (Class<?> i : cls.getInterfaces()) {
            if (i.getAnnotation(Path.class) != null) {
                return i;    
            }
        }
        return cls;
    }
    
    private ClassDocs getClassDocInternal(ClassResourceInfo cri) throws Exception {
        Class<?> annotatedClass = getPathAnnotatedClass(cri.getServiceClass());
        String resource = annotatedClass.getName().replace(".", "/") + ".html";
        ClassDocs classDocs = docs.get(resource);
        if (classDocs == null) {
            InputStream resourceStream = javaDocLoader.getResourceAsStream(resource);
            if (resourceStream != null) {
                String doc = IOUtils.readStringFromStream(resourceStream);
                
                String qualifier = annotatedClass.isInterface() ? "Interface" : "Class"; 
                String classMarker = qualifier + " " + annotatedClass.getSimpleName();
                int index = doc.indexOf(classMarker);
                if (index != -1) {
                    String classInfoTag = getClassInfoTag();
                    String classInfo = getJavaDocText(doc, classInfoTag, 
                                                      "Method Summary", index + classMarker.length());
                    classDocs = new ClassDocs(doc, classInfo);
                    docs.putIfAbsent(resource, classDocs);
                }
            }
        }
        return classDocs;
    }
    
    
    private MethodDocs getOperationDocInternal(OperationResourceInfo ori) throws Exception {
        ClassDocs classDoc = getClassDocInternal(ori.getClassResourceInfo());
        if (classDoc == null) {
            return null;
        }
        Method method = ori.getAnnotatedMethod() == null ? ori.getMethodToInvoke() 
            : ori.getAnnotatedMethod(); 
        MethodDocs mDocs = classDoc.getMethodDocs(method);
        if (mDocs == null) {
            String operLink = getOperLink();
            String operMarker = operLink + method.getName() + "(";
            
            int operMarkerIndex = classDoc.getClassDoc().indexOf(operMarker);
            while (operMarkerIndex != -1) { 
                int startOfOpSigIndex = operMarkerIndex + operMarker.length();
                int endOfOpSigIndex = classDoc.getClassDoc().indexOf(")", operMarkerIndex);
                int paramLen = method.getParameterTypes().length;
                if (endOfOpSigIndex == startOfOpSigIndex + 1 && paramLen == 0) {
                    break;
                } else if (endOfOpSigIndex > startOfOpSigIndex + 1) {
                    String[] opBits = 
                        classDoc.getClassDoc().substring(operMarkerIndex, endOfOpSigIndex).split(",");
                    if (opBits.length == paramLen) {
                        break;
                    }
                }
                operMarkerIndex = classDoc.getClassDoc().indexOf(operMarker, 
                                                                 operMarkerIndex + operMarker.length());
            }
            
            if (operMarkerIndex == -1) { 
                return null;
            }
            
            String operDoc = classDoc.getClassDoc().substring(operMarkerIndex + operMarker.length());
            String operInfoTag = getOperInfoTag();
            String operInfo = getJavaDocText(operDoc, operInfoTag, operLink, 0);
            String responseInfo = null;
            List<String> paramDocs = new LinkedList<String>();
            if (!StringUtils.isEmpty(operInfo)) {
                int returnsIndex = operDoc.indexOf("Returns:", operLink.length());
                int nextOpIndex = operDoc.indexOf(operLink);
                if (returnsIndex != -1 && (nextOpIndex > returnsIndex || nextOpIndex == -1)) {
                    responseInfo = getJavaDocText(operDoc, getResponseMarker(), operLink, returnsIndex + 8);
                }
            
                int paramIndex = operDoc.indexOf("Parameters:");
                if (paramIndex != -1 && (nextOpIndex == -1 || paramIndex < nextOpIndex)) {
                    String paramString = returnsIndex == -1 ? operDoc.substring(paramIndex)
                        : operDoc.substring(paramIndex, returnsIndex); 
                    
                    String codeTag = getCodeTag(); 
                    
                    int codeIndex = paramString.indexOf(codeTag);
                    while (codeIndex != -1) {
                        int next = paramString.indexOf("<", codeIndex + 7);
                        if (next == -1) {
                            next = paramString.length();
                        }
                        String param = paramString.substring(codeIndex + 7, next).trim();
                        if (param.startsWith("-")) {
                            param = param.substring(1).trim();
                        }
                        paramDocs.add(param);
                        if (next == paramString.length()) {
                            break;
                        } else {
                            codeIndex = next + 1;    
                        }
                        codeIndex = paramString.indexOf(codeTag, codeIndex);
                    }
                    
                }
            }
            mDocs = new MethodDocs(operInfo, paramDocs, responseInfo);
            classDoc.addMethodDocs(method, mDocs);
        }
        
        return mDocs;
    }
 
    
    
    private String getJavaDocText(String doc, String tag, String notAfterTag, int index) {
        int tagIndex = doc.indexOf(tag, index);
        if (tagIndex != -1) {
            int notAfterIndex = doc.indexOf(notAfterTag, index);
            if (notAfterIndex == -1 || notAfterIndex > tagIndex) {
                int nextIndex = doc.indexOf("<", tagIndex + tag.length());
                if (nextIndex != -1) {
                    return doc.substring(tagIndex + tag.length(), nextIndex).trim();
                }
            }
        }
        return null;
    }
    
    protected String getClassInfoTag() {
        if (javaDocsBuiltByVersion == JAVA_VERSION_16) {
            return "<P>";
        } else {
            return "<div class=\"block\">";
        }
    }
    protected String getOperInfoTag() {
        if (javaDocsBuiltByVersion == JAVA_VERSION_16) {
            return "<DD>";
        } else {
            return "<div class=\"block\">";
        }
    }
    protected String getOperLink() {
        String operLink = "<A NAME=\"";
        return javaDocsBuiltByVersion == JAVA_VERSION_16 ? operLink : operLink.toLowerCase();
    }
    
    protected String getResponseMarker() {
        String tag = "<DD>";
        return javaDocsBuiltByVersion == JAVA_VERSION_16 ? tag : tag.toLowerCase();
    }
    
    protected String getCodeTag() {
        String tag = "</CODE>";
        return javaDocsBuiltByVersion == JAVA_VERSION_16 ? tag : tag.toLowerCase();
    }
    
    public void setJavaDocsBuiltByVersion(String version) {
        javaDocsBuiltByVersion = Double.valueOf(version);
    }
    
    private static class ClassDocs {
        private String classDoc;
        private String classInfo;
        private ConcurrentHashMap<Method, MethodDocs> mdocs = new ConcurrentHashMap<Method, MethodDocs>(); 
        public ClassDocs(String classDoc, String classInfo) {
            this.classDoc = classDoc;
            this.classInfo = classInfo;
        }
        
        public String getClassDoc() {
            return classDoc;
        }
        
        public String getClassInfo() {
            return classInfo;
        }
        
        public MethodDocs getMethodDocs(Method method) {
            return mdocs.get(method);
        }
        
        public void addMethodDocs(Method method, MethodDocs doc) {
            mdocs.putIfAbsent(method, doc);
        }
    }
    
    private static class MethodDocs {
        private String methodInfo;
        private List<String> paramInfo = new LinkedList<String>();
        private String responseInfo;
        public MethodDocs(String methodInfo, List<String> paramInfo, String responseInfo) {
            this.methodInfo = methodInfo;
            this.paramInfo = paramInfo;
            this.responseInfo = responseInfo;
        }
        
        public String getMethodInfo() {
            return methodInfo;
        }
        
        public List<String> getParamInfo() {
            return paramInfo;
        }
        
        public String getResponseInfo() {
            return responseInfo;
        }
    }
}
