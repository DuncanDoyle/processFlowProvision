/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.processFlow.knowledgeService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.util.*;
import javax.management.ObjectName;
import javax.management.MBeanServer;
import javax.transaction.UserTransaction;
import javax.persistence.*;

import org.apache.log4j.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import org.drools.SessionConfiguration;
import org.drools.SystemEventListener;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.WorkingMemory;
import org.drools.agent.KnowledgeAgentConfiguration;
import org.drools.agent.KnowledgeAgent;
import org.drools.agent.KnowledgeAgentFactory;
import org.drools.command.impl.CommandBasedStatefulKnowledgeSession;
import org.drools.command.impl.KnowledgeCommandContext;
import org.drools.compiler.PackageBuilder;
import org.drools.definition.process.Process;
import org.drools.definitions.impl.KnowledgePackageImp;
import org.drools.definition.KnowledgePackage;
import org.drools.definition.process.WorkflowProcess;
import org.drools.definition.process.Node;
import org.drools.event.*;
import org.drools.impl.StatefulKnowledgeSessionImpl;
import org.drools.io.*;
import org.drools.io.impl.InputStreamResource;
import org.drools.management.DroolsManagementAgent;
import org.drools.persistence.jpa.JPAKnowledgeService;
import org.drools.runtime.KnowledgeSessionConfiguration;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.process.ProcessInstance;
import org.drools.runtime.process.WorkItemHandler;
import org.jbpm.workflow.core.NodeContainer;
import org.jbpm.compiler.ProcessBuilderImpl;
import org.jbpm.integration.console.shared.GuvnorConnectionUtils;
import org.jbpm.task.service.TaskService;

import org.jboss.processFlow.knowledgeService.IKnowledgeSessionService;
import org.jboss.processFlow.tasks.ITaskService;
import org.jboss.processFlow.workItem.WorkItemHandlerLifecycle;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

public class BaseKnowledgeSessionBean {

    public static final String EMF_NAME = "org.jbpm.persistence.jpa";
    public static final String DROOLS_SESSION_CONF_PATH="/META-INF/drools.session.conf";
    public static final String DROOLS_SESSION_TEMPLATE_PATH="drools.session.template.path";
    public static final String DROOLS_WORK_ITEM_HANDLERS = "drools.workItemHandlers";
    
    protected Logger log = Logger.getLogger(BaseKnowledgeSessionBean.class);
    protected String droolsResourceScannerInterval = "30";
    protected boolean enableLog = false;
    protected boolean enableKnowledgeRuntimeLogger = true;
    protected Map<String, Class> programmaticallyLoadedWorkItemHandlers = new HashMap<String, Class>();

    protected KnowledgeBase kbase = null;
    protected SystemEventListener originalSystemEventListener = null;
    protected DroolsManagementAgent kmanagement = null;
    protected GuvnorConnectionUtils guvnorUtils = null;
    protected Properties ksconfigProperties;
    protected String[] processEventListeners;
    protected String guvnorChangeSet;
    
    protected Properties guvnorProps;
    protected String taskCleanUpImpl;
    protected String templateString;
    protected boolean sessionTemplateInstantiationAlreadyBombed = false;
    
    /* static variable because :
     *   1)  TaskService is a thread-safe object
     *   2)  TaskService is needed for both :
     *     - PFP HumanTaskService           :   functions using a jta enable entity manager for human task functionality
     *     - PFP KnowledgeSessionService    :   needed to instantiate TasksAdmin object and register with knowledgeSession
     */
    protected static TaskService jtaTaskService;
    

    protected @PersistenceUnit(unitName=EMF_NAME)  EntityManagerFactory jbpmCoreEMF;
    protected @javax.annotation.Resource UserTransaction uTrnx;
    

/******************************************************************************
 * *************        Drools KnowledgeBase Management               *********/
    
    // critical that each StatefulKnowledgeSession have its own JPA 'Environment'
    protected Environment createKnowledgeSessionEnvironment() {
        Environment env = KnowledgeBaseFactory.newEnvironment();
        env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, jbpmCoreEMF);
        return env;
    }
    
    public void createKnowledgeBaseViaKnowledgeAgentOrBuilder() {
        try {
            this.createKnowledgeBaseViaKnowledgeAgent();
        }catch(ConnectException x){
            log.warn("createKnowledgeBaseViaKnowledgeAgentOrBuilder() can not create a kbase via a kagent due to a connection problem with guvnor ... will now create kbase via knowledgeBuilder");
            rebuildKnowledgeBaseViaKnowledgeBuilder();
        }
    }

    public void createOrRebuildKnowledgeBaseViaKnowledgeAgentOrBuilder() {
        try {
            this.createKnowledgeBaseViaKnowledgeAgent(true);
        }catch(ConnectException x){
            log.warn("createKnowledgeBaseViaKnowledgeAgentOrBuilder() can not create a kbase via a kagent due to a connection problem with guvnor ... will now create kbase via knowledgeBuilder");
            rebuildKnowledgeBaseViaKnowledgeBuilder();
        }
    }
    
    public void rebuildKnowledgeBaseViaKnowledgeAgent() throws ConnectException{
        this.createKnowledgeBaseViaKnowledgeAgent(true);
    }
    protected void createKnowledgeBaseViaKnowledgeAgent() throws ConnectException{
        this.createKnowledgeBaseViaKnowledgeAgent(false);
    }

    // only one knowledgeBase object is needed and is shared amongst all StatefulKnowledgeSessions
    // needs to be invoked AFTER guvnor is available (obviously)
    // setting 'force' parameter to true re-creates an existing kbase
    protected synchronized void createKnowledgeBaseViaKnowledgeAgent(boolean forceRefresh) throws ConnectException{
        if(kbase != null && !forceRefresh)
            return;

        // investigate:  List<String> guvnorPackages = guvnorUtils.getBuiltPackageNames();
        // http://ratwateribm:8080/jboss-brms/org.drools.guvnor.Guvnor/package/org.jboss.processFlow/test-pfp-snapshot

        if(!guvnorUtils.guvnorExists()) {
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append(guvnorUtils.getGuvnorProtocol());
            sBuilder.append("://");
            sBuilder.append(guvnorUtils.getGuvnorHost());
            sBuilder.append("/");
            sBuilder.append(guvnorUtils.getGuvnorSubdomain());
            sBuilder.append("/rest/packages/");
            throw new ConnectException("createKnowledgeBase() cannot connect to guvnor at URL : "+sBuilder.toString()); 
        }

        // for polling of guvnor to occur, the polling and notifier services must be started
        ResourceChangeScannerConfiguration sconf = ResourceFactory.getResourceChangeScannerService().newResourceChangeScannerConfiguration();
        sconf.setProperty( "drools.resource.scanner.interval", droolsResourceScannerInterval);
        ResourceFactory.getResourceChangeScannerService().configure( sconf );
        ResourceFactory.getResourceChangeScannerService().start();
        ResourceFactory.getResourceChangeNotifierService().start();
        
        KnowledgeAgentConfiguration aconf = KnowledgeAgentFactory.newKnowledgeAgentConfiguration(); // implementation = org.drools.agent.impl.KnowledgeAgentConfigurationImpl

        /*  - incremental change set processing enabled
            - will create a single KnowledgeBase and always refresh that same instance
        */
        aconf.setProperty("drools.agent.newInstance", "false");

        /*  -- Knowledge Agent provides automatic loading, caching and re-loading of resources
            -- the knowledge agent can update or rebuild this knowledge base as the resources it uses are changed
        */
        KnowledgeAgent kagent = KnowledgeAgentFactory.newKnowledgeAgent("Guvnor default", aconf);
        StringReader sReader = guvnorUtils.createChangeSet();
        try {
            guvnorChangeSet = IOUtils.toString(sReader);
            sReader.close();
        }catch(Exception x){
            x.printStackTrace();
        }
        
        kagent.applyChangeSet(ResourceFactory.newByteArrayResource(guvnorChangeSet.getBytes()));

        /*  - set KnowledgeBase as instance variable to this mbean for use throughout all functionality of this service
            - a knowledge base is a collection of compiled definitions, such as rules and processes, which are compiled using the KnowledgeBuilder
            - the knowledge base itself does not contain instance data, known as facts
            - instead, sessions are created from the knowledge base into which data can be inserted and where process instances may be started
            - creating the knowledge base can be heavy, whereas session creation is very light :  http://blog.athico.com/2011/09/small-efforts-big-improvements.html
            - a knowledge base is also serializable, allowing for it to be stored
        */
        kbase = kagent.getKnowledgeBase();
    }
    
    public void rebuildKnowledgeBaseViaKnowledgeBuilder() {
        guvnorProps = new Properties();
        try {
            KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
            if(guvnorUtils.guvnorExists()) {
                guvnorProps.load(BaseKnowledgeSessionBean.class.getResourceAsStream("/jbpm.console.properties"));
                StringBuilder guvnorSBuilder = new StringBuilder();
                guvnorSBuilder.append(guvnorProps.getProperty(GuvnorConnectionUtils.GUVNOR_PROTOCOL_KEY));
                guvnorSBuilder.append("://");
                guvnorSBuilder.append(guvnorProps.getProperty(GuvnorConnectionUtils.GUVNOR_HOST_KEY));
                guvnorSBuilder.append("/");
                guvnorSBuilder.append(guvnorProps.getProperty(GuvnorConnectionUtils.GUVNOR_SUBDOMAIN_KEY));
                String guvnorURI = guvnorSBuilder.toString();
                List<String> packages = guvnorUtils.getPackageNames();
                for(String pkg : packages){
                    GuvnorRestApi guvnorRestApi = new GuvnorRestApi(guvnorURI);
                    try {
                        InputStream binaryPackage = guvnorRestApi.getBinaryPackage(pkg);
                        kbuilder.add(new InputStreamResource(binaryPackage), ResourceType.PKG);
                        guvnorRestApi.close();
                    } catch(java.io.IOException y) {
                        log.error("rebuildKnowledgeBaseViaKnowledgeBuilder() returned following exception when querying package = "+pkg+" : "+y);
                    }
               }
            }
            kbase = kbuilder.newKnowledgeBase();
        }catch(Exception x){
            throw new RuntimeException(x);
        }
    }
   
    // compile a process into a package and add it to the knowledge base 
    public void addProcessToKnowledgeBase(Process processObj, Resource resourceObj) {
        if(kbase == null)
            rebuildKnowledgeBaseViaKnowledgeBuilder();
       
        PackageBuilder packageBuilder = new PackageBuilder();
        ProcessBuilderImpl processBuilder = new ProcessBuilderImpl( packageBuilder );
        processBuilder.buildProcess( processObj, resourceObj);

        List<KnowledgePackage> kpackages = new ArrayList<KnowledgePackage>();
        kpackages.add( new KnowledgePackageImp( packageBuilder.getPackage() ) );
        kbase.addKnowledgePackages(kpackages);
        log.info("addProcessToKnowledgeBase() just added the following bpmn2 process definition to the kbase: "+processObj.getId());
    }

    public void addProcessToKnowledgeBase(File bpmnFile) {
        if(kbase == null)
            rebuildKnowledgeBaseViaKnowledgeBuilder();

        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        kbuilder.add(ResourceFactory.newFileResource(bpmnFile), ResourceType.BPMN2);
        kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
        log.info("addProcessToKnowledgeBase() just added the following bpmn2 process definition to the kbase: "+bpmnFile.getName());
    }
    
    public String getAllProcessesInPackage(String pkgName){
        List<String> processes = guvnorUtils.getAllProcessesInPackage(pkgName);
        StringBuilder sBuilder = new StringBuilder("getAllProcessesInPackage() pkgName = "+pkgName);
        if(processes.isEmpty()){
            sBuilder.append("\n\n\t :  not processes found");
            return sBuilder.toString();
        }
        for(String pDef : processes){
            sBuilder.append("\n\t");
            sBuilder.append(pDef);
        }
        return sBuilder.toString();
    }
    
    public String printKnowledgeBaseContent() {
        if(kbase == null)
            createKnowledgeBaseViaKnowledgeAgentOrBuilder();

        StringBuilder sBuilder = new StringBuilder();
        sBuilder.append("guvnor changesets:\n\t");
       
        if(guvnorChangeSet != null) 
            sBuilder.append(guvnorChangeSet);
        else
            sBuilder.append("not yet created by knowledgeAgent");

        Collection<KnowledgePackage> kPackages = kbase.getKnowledgePackages();
        if(kPackages != null && kPackages.size() > 0) {
            for(KnowledgePackage kPackage : kPackages){
                Collection<Process> processes = kPackage.getProcesses();
                if(processes.size() == 0){
                    sBuilder.append("\n\tpackage = "+kPackage.getName()+" : no process definitions found ");
                }else {

                    sBuilder.append("\nprintKnowledgeBaseContent()\n\t"); 
                    for (Process process : processes) {
                        sBuilder.append("\n\tpackage = "+kPackage.getName()+" : process definition = " + process.getId());
                    }
                }
            }
        } else {
            sBuilder.append("\n\nNo Packages found in kbase");
        }
        sBuilder.append("\n");
        return sBuilder.toString();
    }
    
    protected SessionTemplate newSessionTemplate() {
        if(sessionTemplateInstantiationAlreadyBombed)
            return null;
        
        if(templateString == null){
            String droolsSessionTemplatePath = System.getProperty(DROOLS_SESSION_TEMPLATE_PATH);
            if(StringUtils.isNotEmpty(droolsSessionTemplatePath)){
                File droolsSessionTemplate = new File(droolsSessionTemplatePath);
                if(!droolsSessionTemplate.exists()) {
                    throw new RuntimeException("newSessionTemplate() drools session template not found at : "+droolsSessionTemplatePath);
                }else {
                    FileInputStream fStream = null;
                    try {
                        fStream = new FileInputStream(droolsSessionTemplate);
                        templateString = IOUtils.toString(fStream);

                    }catch(IOException x){
                        x.printStackTrace();
                    }finally {
                        if(fStream != null) {
                            try {fStream.close(); }catch(Exception x){x.printStackTrace();}
                        }
                    }
                }
            }else {
                throw new RuntimeException("newSessionTemplate() following property must be defined : "+DROOLS_SESSION_TEMPLATE_PATH);
            }
        }
        ParserConfiguration pconf = new ParserConfiguration();
        pconf.addImport("SessionTemplate", SessionTemplate.class);
        ParserContext context = new ParserContext(pconf);
        Serializable s = MVEL.compileExpression(templateString.trim(), context);
        try {
            return (SessionTemplate)MVEL.executeExpression(s);
        }catch(Throwable x){
            sessionTemplateInstantiationAlreadyBombed = true;
            log.error("newSessionTemplate() following exception thrown \n\t"+x.getLocalizedMessage()+"\n : with session template string = \n\n"+templateString);
            return null;
        }
    }

    
    
    

/******************************************************************************
 * *************            WorkItemHandler Management               *********/
    
    public String printWorkItemHandlers() { 
        StringBuilder sBuilder = new StringBuilder("Programmatically Loaded Work Item Handlers :");
        for(String name : programmaticallyLoadedWorkItemHandlers.keySet()){
           sBuilder.append("\n\t"); 
           sBuilder.append(name); 
           sBuilder.append(" : "); 
           sBuilder.append(programmaticallyLoadedWorkItemHandlers.get(name)); 
        }
        sBuilder.append("\nWork Item Handlers loaded from drools session template:");
        SessionTemplate sTemplate = newSessionTemplate();
        if(sTemplate != null){
            for(Map.Entry<?, ?> entry : sTemplate.getWorkItemHandlers().entrySet()){
                Class wiClass = entry.getValue().getClass();
                sBuilder.append("\n\t"); 
                sBuilder.append(entry.getKey()); 
                sBuilder.append(" : "); 
                sBuilder.append(wiClass.getClass());
            }
        }else {
            sBuilder.append("\n\tsessionTemplate not instantiated ... check previous exceptions");
        }
        sBuilder.append("\nConfiguration Loaded Work Item Handlers :");
        SessionConfiguration ksConfig = (SessionConfiguration)KnowledgeBaseFactory.newKnowledgeSessionConfiguration(ksconfigProperties);
        try {
            Map<String, WorkItemHandler> wiHandlers = ksConfig.getWorkItemHandlers();
            if(wiHandlers.size() == 0) {
                sBuilder.append("\n\t no work item handlers defined");
                Properties badProps = createPropsFromDroolsSessionConf();
                if(badProps == null)
                    sBuilder.append("\n\tunable to locate "+DROOLS_SESSION_CONF_PATH);
                else
                    sBuilder.append("\n\tlocated"+DROOLS_SESSION_CONF_PATH);
            } else {
                for(String name : wiHandlers.keySet()){
                    sBuilder.append("\n\t"); 
                    sBuilder.append(name); 
                    sBuilder.append(" : "); 
                    Class wiClass = wiHandlers.get(name).getClass();
                    sBuilder.append(wiClass); 
                }
            }
        }catch(NullPointerException x){
            sBuilder.append("\n\tError intializing at least one of the configured work item handlers via drools.session.conf.\n\tEnsure all space delimited work item handlers listed in drools.session.conf exist on the classpath");
            Properties badProps = createPropsFromDroolsSessionConf();
            if(badProps == null){
                sBuilder.append("\n\tunable to locate "+DROOLS_SESSION_CONF_PATH);
            } else {
                try {
                    Enumeration badEnums = badProps.propertyNames();
                    while (badEnums.hasMoreElements()) {
                        String handlerConfName = (String) badEnums.nextElement();
                        if(DROOLS_WORK_ITEM_HANDLERS.equals(handlerConfName)) {
                            String[] badHandlerNames = ((String)badProps.get(handlerConfName)).split("\\s");
                            for(String badHandlerName : badHandlerNames){
                                sBuilder.append("\n\t\t");
                                sBuilder.append(badHandlerName);
                                InputStream iStream = this.getClass().getResourceAsStream("/META-INF/"+badHandlerName);
                                if(iStream != null){
                                    sBuilder.append("\t : found on classpath");
                                    iStream.close();
                                } else {
                                    sBuilder.append("\t : NOT FOUND on classpath !!!!!  ");
                                }
                            }
                        }
                    }
                } catch (Exception y) {
                    y.printStackTrace();
                }
            }
        }catch(org.mvel2.CompileException x) {
            sBuilder.append("\n\t located "+DROOLS_SESSION_CONF_PATH);
            sBuilder.append("\n\t however, following ClassNotFoundException encountered when instantiating defined work item handlers : \n\t\t");
            sBuilder.append(x.getLocalizedMessage());
        }
        sBuilder.append("\n"); 
        return sBuilder.toString();
    }
    
    private Properties createPropsFromDroolsSessionConf() {
        Properties badProps = null;
        InputStream iStream = null;
        try {
            iStream = this.getClass().getResourceAsStream(DROOLS_SESSION_CONF_PATH);
            if(iStream != null){
                badProps = new Properties();
                badProps.load(iStream);
                iStream.close();
            }
        } catch(Exception x) {
            x.printStackTrace();
        }
        return badProps; 
    }
    
    
    protected void registerWorkItemHandler(StatefulKnowledgeSession ksession, String serviceTaskName, WorkItemHandlerLifecycle handler) {
        try {
            ksession.getWorkItemManager().registerWorkItemHandler(serviceTaskName, handler);
        } catch(NullPointerException x) {
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append("registerHumanTaskWorkItemHandler() ********* NullPointerException when attempting to programmatically register workItemHander of type: "+serviceTaskName);
            sBuilder.append("\nthe following is a report of your work item situation: \n\n");
            sBuilder.append(printWorkItemHandlers());
            sBuilder.append("\n");
            log.error(sBuilder);
            throw x;
        }
    }
    
    protected void registerAddHumanTaskWorkItemHandler(StatefulKnowledgeSession ksession) {
        try {
            // 1.  instantiate an object and register with this session workItemManager 
            Class workItemHandlerClass = programmaticallyLoadedWorkItemHandlers.get(ITaskService.HUMAN_TASK);
            WorkItemHandlerLifecycle handler = (WorkItemHandlerLifecycle)workItemHandlerClass.newInstance();

            // 2.  register workItemHandler with workItemManager
            registerWorkItemHandler(ksession, ITaskService.HUMAN_TASK, handler);

            // 3).  call init() on newly instantiated WorkItemHandlerLifecycle
            handler.init(ksession);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    protected void registerSkipHumanTaskWorkItemHandler(StatefulKnowledgeSession ksession){
        try {
            Class workItemHandlerClass = programmaticallyLoadedWorkItemHandlers.get(ITaskService.SKIP_TASK);
            WorkItemHandlerLifecycle handler = (WorkItemHandlerLifecycle)workItemHandlerClass.newInstance();
            registerWorkItemHandler(ksession, ITaskService.SKIP_TASK, handler);
            handler.init(ksession);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    protected void registerFailHumanTaskWorkItemHandler(StatefulKnowledgeSession ksession){
        try {
            Class workItemHandlerClass = programmaticallyLoadedWorkItemHandlers.get(ITaskService.FAIL_TASK);
            WorkItemHandlerLifecycle handler = (WorkItemHandlerLifecycle)workItemHandlerClass.newInstance();
            registerWorkItemHandler(ksession, ITaskService.FAIL_TASK, handler);
            handler.init(ksession);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }

    protected void registerEmailWorkItemHandler(StatefulKnowledgeSession ksession) {
        String address = System.getProperty("org.jbpm.workItemHandler.mail.address");
        String port = System.getProperty("org.jbpm.workItemHandler.mail.port");
        String userId = System.getProperty("org.jbpm.workItemHandler.mail.userId");
        String password = System.getProperty("org.jbpm.workItemHandler.mail.password");
        WorkItemHandlerLifecycle handler = null;
        try {
            Class workItemHandlerClass = programmaticallyLoadedWorkItemHandlers.get(IKnowledgeSessionService.EMAIL);
            Class[] classParams = new Class[] {String.class, String.class, String.class, String.class};
            Object[] objParams = new Object[] {address, port, userId, password};
            Constructor cObj = workItemHandlerClass.getConstructor(classParams);
            handler = (WorkItemHandlerLifecycle)cObj.newInstance(objParams);
            registerWorkItemHandler(ksession, IKnowledgeSessionService.EMAIL, handler);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }

    
    
    
    
    
/******************************************************************************
 * *************    ProcessEventListener Management                  *********/    
    
    // listens for agenda changes like rules being activated, fired, cancelled, etc
    protected void addAgendaEventListener(Object ksession) {
        final org.drools.event.AgendaEventListener agendaEventListener = new org.drools.event.AgendaEventListener() {
            public void activationCreated(ActivationCreatedEvent event, WorkingMemory workingMemory){
            }
            public void activationCancelled(ActivationCancelledEvent event, WorkingMemory workingMemory){
            }
            public void beforeActivationFired(BeforeActivationFiredEvent event, WorkingMemory workingMemory) {
            }
            public void afterActivationFired(AfterActivationFiredEvent event, WorkingMemory workingMemory) {
            }
            public void agendaGroupPopped(AgendaGroupPoppedEvent event, WorkingMemory workingMemory) {
            }
            public void agendaGroupPushed(AgendaGroupPushedEvent event, WorkingMemory workingMemory) {
            }
            public void beforeRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event, WorkingMemory workingMemory) {
            }
            public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event, WorkingMemory workingMemory) {
                workingMemory.fireAllRules();
            }
            public void beforeRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event, WorkingMemory workingMemory) {
            }
            public void afterRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event,  WorkingMemory workingMemory) {
            }
        };
        ((StatefulKnowledgeSessionImpl)  ((KnowledgeCommandContext) ((CommandBasedStatefulKnowledgeSession) ksession)
                    .getCommandService().getContext()).getStatefulKnowledgesession() )
                    .session.addEventListener(agendaEventListener);
    }
    
    
    
/******************************************************************************
 *************        StatefulKnowledgeSession Management               *********/
    
    protected StatefulKnowledgeSession makeStatefulKnowledgeSession() {
        // 1) instantiate a KnowledgeBase via query to guvnor or kbuilder
        createKnowledgeBaseViaKnowledgeAgentOrBuilder();

        // 2) very important that a unique 'Environment' is created per StatefulKnowledgeSession
        Environment ksEnv = createKnowledgeSessionEnvironment();

        // Nick: always instantiate new ksconfig to make it threadlocal bo bapass the ConcurrentModificationExcepotion
        KnowledgeSessionConfiguration ksConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration(ksconfigProperties);

        // 3) instantiate StatefulKnowledgeSession
        //    make synchronize because under heavy load, appears that underlying SessionInfo.update() breaks with a NPE
        StatefulKnowledgeSession ksession = JPAKnowledgeService.newStatefulKnowledgeSession(kbase, ksConfig, ksEnv);
        return ksession;
    }  
    
    
    /******************************************************************************
    *************              Process Definition Management              *********/
        public List<SerializableProcessMetaData> retrieveProcesses() throws Exception {
            List<SerializableProcessMetaData> result = new ArrayList<SerializableProcessMetaData>();
            if(kbase == null)
                createKnowledgeBaseViaKnowledgeAgent();
            for (KnowledgePackage kpackage: kbase.getKnowledgePackages()) {
                for(Process processObj : kpackage.getProcesses()){
                    Long pVersion = 0L;
                    if(!StringUtils.isEmpty(processObj.getVersion()))
                        pVersion = Long.parseLong(processObj.getVersion());
                    result.add(getProcess(processObj.getId()));
                }
            }
            log.info("getProcesses() # of processes = "+result.size());
            return result;
        }

        public SerializableProcessMetaData getProcess(String processId) {
            if(kbase == null)
                createKnowledgeBaseViaKnowledgeAgentOrBuilder();
            Process processObj = kbase.getProcess(processId);
            Long pVersion = 0L;
            if(!StringUtils.isEmpty(processObj.getVersion()))
                pVersion = Long.parseLong(processObj.getVersion());
            SerializableProcessMetaData spObj = new SerializableProcessMetaData(processObj.getId(), processObj.getName(), pVersion, processObj.getPackageName());
            if (processObj instanceof org.drools.definition.process.WorkflowProcess) {
                Node[] nodes = ((WorkflowProcess)processObj).getNodes();
                addNodesInfo(spObj.getNodes(), nodes, "id=");
            }
            return spObj;
        }
        private void addNodesInfo(List<SerializableNodeMetaData> snList, Node[] nodes, String prefix) {
            for(Node nodeObj : nodes) {
                // JA Bride:  AsyncBAMProducer has been modified from stock jbpm5 to persist the "uniqueNodeId" in the jbpm_bam database
                //  (as opposed to persisting just the simplistic nodeId)
                //  will need to invoke same functionality here to calculate 'uniqueNodeId' 
                String uniqueId = org.jbpm.bpmn2.xml.XmlBPMNProcessDumper.getUniqueNodeId(nodeObj);
                SerializableNodeMetaData snObj = new SerializableNodeMetaData(
                        (Integer)nodeObj.getMetaData().get(SerializableNodeMetaData.X),
                        (Integer)nodeObj.getMetaData().get(SerializableNodeMetaData.Y),
                        (Integer)nodeObj.getMetaData().get(SerializableNodeMetaData.HEIGHT),
                        (Integer)nodeObj.getMetaData().get(SerializableNodeMetaData.WIDTH),
                        uniqueId                                                      
                        );
                snList.add(snObj);
                if (nodeObj instanceof NodeContainer) {
                    addNodesInfo(snObj.getNodes(), ((NodeContainer)nodeObj).getNodes(), prefix + nodeObj.getId() + ":");
                }
            }
        }
    
    public void removeProcess(String processId) {
        throw new UnsupportedOperationException();
    }

    public List<ProcessInstance> getActiveProcessInstances(Map<String, Object> queryCriteria) {
         EntityManager psqlEm = null;
         List<ProcessInstance> results = null;
         StringBuilder sqlBuilder = new StringBuilder();
         sqlBuilder.append("FROM ProcessInstance p ");
         if(queryCriteria != null && queryCriteria.size() > 0){
             sqlBuilder.append("WHERE ");
             if(queryCriteria.containsKey(IKnowledgeSessionService.PROCESS_ID)){
                 sqlBuilder.append("p.processid = :processId");
             }
         }
         try {
             psqlEm = jbpmCoreEMF.createEntityManager();
             Query processInstanceQuery = psqlEm.createQuery(sqlBuilder.toString());
             if(queryCriteria != null && queryCriteria.size() > 0){
                 if(queryCriteria.containsKey(IKnowledgeSessionService.PROCESS_ID)){
                     processInstanceQuery = processInstanceQuery.setParameter(IKnowledgeSessionService.PROCESS_ID, queryCriteria.get(IKnowledgeSessionService.PROCESS_ID));
                 }
             }
             results = processInstanceQuery.getResultList();
             return results;
         }catch(Exception x) {
             return null;
         }
     }
}