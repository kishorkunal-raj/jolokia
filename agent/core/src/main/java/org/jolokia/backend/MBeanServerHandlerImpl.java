package org.jolokia.backend;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import javax.management.*;

import org.jolokia.backend.executor.MBeanServerExecutor;
import org.jolokia.backend.executor.NotChangedException;
import org.jolokia.config.*;
import org.jolokia.detector.*;
import org.jolokia.handler.CommandHandler;
import org.jolokia.request.JmxRequest;
import org.jolokia.service.impl.LocalServiceFactory;
import org.jolokia.util.LogHandler;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * Handler for finding and merging various MBeanServers locally when used
 * as an agent.
 *
 * @author roland
 * @since Jun 15, 2009
 */
public class MBeanServerHandlerImpl implements MBeanServerHandlerImplMBean, MBeanRegistration, MBeanServerHandler {

    // The object dealing with all MBeanServers
    private MBeanServerExecutorLocal mBeanServerManager;

    // Optional domain for registering this handler as a MBean
    private String qualifier;

    // Information about the server environment
    private ServerHandle serverHandle;

    // Handles remembered for unregistering
    private final List<MBeanHandle> mBeanHandles = new ArrayList<MBeanHandle>();

    /**
     * Create a new MBeanServer handler who is responsible for managing multiple intra VM {@link MBeanServer} at once
     * An optional qualifier used for registering this object as an MBean is taken from the given configuration as well
     *
     * @param pConfig configuration for this agent which is also given to the {@see ServerHandle#postDetect()} method for
     *                special initialization.
     *
     * @param pLogHandler log handler used for logging purposes
     */
    public MBeanServerHandlerImpl(Configuration pConfig, LogHandler pLogHandler) {
        // A qualifier, if given, is used to add the MBean Name of this MBean
        qualifier = pConfig.getConfig(ConfigKey.MBEAN_QUALIFIER);
        List<ServerDetector> detectors = lookupDetectors();
        mBeanServerManager = new MBeanServerExecutorLocal(detectors);
        initServerHandle(pConfig, pLogHandler, detectors);
        initMBean();
    }

    /**
     * Extract a unique Id for this agent
     *
     * @param pConfig configuration given
     * @return the unique Jolokia ID
     */
    private String extractJolokiaId(Configuration pConfig) {
        String id = pConfig.getConfig(ConfigKey.JOLOKIA_ID);
        if (id != null) {
            return id;
        }
        return Integer.toHexString(hashCode()) + "-unknown";
    }

    /** {@inheritDoc} */
    public Object dispatch(CommandHandler pRequestHandler, JmxRequest pJmxReq)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, NotChangedException {
        serverHandle.preDispatch(mBeanServerManager,pJmxReq);
        if (pRequestHandler.handleAllServersAtOnce(pJmxReq)) {
            try {
                return pRequestHandler.handleRequest(mBeanServerManager,pJmxReq);
            } catch (IOException e) {
                throw new IllegalStateException("Internal: IOException " + e + ". Shouldn't happen.",e);
            }
        } else {
            return mBeanServerManager.handleRequest(pRequestHandler, pJmxReq);
        }
    }

    /** {@inheritDoc} */
    public final ObjectName registerMBean(Object pMBean, String... pOptionalName)
            throws MalformedObjectNameException, NotCompliantMBeanException, InstanceAlreadyExistsException {
        synchronized (mBeanHandles) {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            try {
                String name = pOptionalName != null && pOptionalName.length > 0 ? pOptionalName[0] : null;
                ObjectName registeredName = serverHandle.registerMBeanAtServer(server, pMBean, name);
                mBeanHandles.add(new MBeanHandle(server,registeredName));
                return registeredName;
            } catch (RuntimeException exp) {
                throw new IllegalStateException("Could not register " + pMBean + ": " + exp, exp);
                } catch (MBeanRegistrationException exp) {
                throw new IllegalStateException("Could not register " + pMBean + ": " + exp, exp);
            }
        }
    }

    /** {@inheritDoc} */
    public final void destroy() throws JMException {
        synchronized (mBeanHandles) {
            List<JMException> exceptions = new ArrayList<JMException>();
            List<MBeanHandle> unregistered = new ArrayList<MBeanHandle>();
            for (MBeanHandle handle : mBeanHandles) {
                try {
                    unregistered.add(handle);
                    handle.server.unregisterMBean(handle.objectName);
                } catch (InstanceNotFoundException e) {
                    exceptions.add(e);
                } catch (MBeanRegistrationException e) {
                    exceptions.add(e);
                }
            }
            // Remove all successfully unregistered handles
            mBeanHandles.removeAll(unregistered);

            // Throw error if any exception occured during unregistration
            if (exceptions.size() == 1) {
                throw exceptions.get(0);
            } else if (exceptions.size() > 1) {
                StringBuilder ret = new StringBuilder();
                for (JMException e : exceptions) {
                    ret.append(e.getMessage()).append(", ");
                }
                throw new JMException(ret.substring(0, ret.length() - 2));
            }
        }

        // Unregister any notification listener
        mBeanServerManager.destroy();
    }

    /** {@inheritDoc} */
    public ServerHandle getServerHandle() {
        return serverHandle;
    }

    // =================================================================================

    /**
     * Initialize the server handle.
     * @param pConfig configuration passed through to the server detectors-default
     * @param pLogHandler used for putting out diagnostic messags
     * @param pDetectors all detectors-default known
     */
    private void initServerHandle(Configuration pConfig, LogHandler pLogHandler, List<ServerDetector> pDetectors) {
        serverHandle = detectServers(pDetectors, pLogHandler);
        serverHandle.postDetect(mBeanServerManager, pConfig, pLogHandler);
        serverHandle.setJolokiaId(extractJolokiaId(pConfig));
    }

    /**
     * Initialise this server handler and register as an MBean
     */
    private void initMBean()  {
        try {
            registerMBean(this,getObjectName());
        } catch (InstanceAlreadyExistsException exp) {
            // This is no problem, since this MBeanServerHandlerMBean holds only global information
            // with no special state (so all instances of this MBean behave similar)
            // This exception can happen, when multiple servlets get registered within the same JVM
        } catch (MalformedObjectNameException e) {
            // Cannot happen, otherwise this is a bug. We should be always provide our own name in correct
            // form.
            throw new IllegalStateException("Internal Error: Own ObjectName " + getObjectName() + " is malformed",e);
        } catch (NotCompliantMBeanException e) {
            // Same here
            throw new IllegalStateException("Internal Error: " + this.getClass().getName() + " is not a compliant MBean",e);
        }
    }

    // Lookup all registered detectors-default + a default detector
    private List<ServerDetector> lookupDetectors() {
        List<ServerDetector> detectors =
                LocalServiceFactory.createServices("META-INF/detectors-default", "META-INF/detectors");
        // An detector at the end of the chain in order to get a default handle
        detectors.add(new FallbackServerDetector());
        return detectors;
    }

    // Detect the server by delegating it to a set of predefined detectors-default. These will be created
    // by a lookup mechanism, queried and thrown away after this method
    private ServerHandle detectServers(List<ServerDetector> pDetectors, LogHandler pLogHandler) {
        // Now detect the server
        for (ServerDetector detector : pDetectors) {
            try {
                ServerHandle info = detector.detect(mBeanServerManager);
                if (info != null) {
                    return info;
                }
            } catch (Exception exp) {
                // We are defensive here and wont stop the servlet because
                // there is a problem with the server detection. A error will be logged
                // nevertheless, though.
                pLogHandler.error("Error while using detector " + detector.getClass().getSimpleName() + ": " + exp,exp);
            }
        }
        return null;
    }

    /**
     * Get the set of MBeanServers found. Useful for testing
     *
     * @return the executor
     */
    MBeanServerExecutorLocal getMBeanServerManager() {
        return mBeanServerManager;
    }

    // =====================================================================================

    // MBean exported debugging method

    /**
     * Get a description of all MBeanServers found
     * @return a description of all MBeanServers along with their stored MBeans
     */
    public String mBeanServersInfo() {
        return mBeanServerManager.getServersInfo();
    }

    // ==============================================================================================
    // Needed for providing the name for our MBean
    /** {@inheritDoc} */
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws MalformedObjectNameException {
        return new ObjectName(getObjectName());
    }

    /** {@inheritDoc} */
    public final String getObjectName() {
        return OBJECT_NAME + (qualifier != null ? "," + qualifier : "");
    }

    /** {@inheritDoc} */
    public void postRegister(Boolean registrationDone) {
    }

    /** {@inheritDoc} */
    public void preDeregister() {
    }

    /** {@inheritDoc} */
    public void postDeregister() {
    }

    // ==================================================================================
    // Handle for remembering registered MBeans
    private static final class MBeanHandle {
        private ObjectName objectName;
        private MBeanServer server;

        private MBeanHandle(MBeanServer pServer, ObjectName pRegisteredName) {
            server = pServer;
            objectName = pRegisteredName;
        }
    }

    // ==================================================================================
    // Fallback server detector which matches always

    private static class NullServerHandle extends ServerHandle {
        /**
         * Empty constructor initializing the server handle completely with null values.
         */
        public NullServerHandle() {
            super(null,null,null,null,null);
        }
    }

    private static class FallbackServerDetector extends AbstractServerDetector {
        /** {@inheritDoc}
         * @param pMBeanServerExecutor*/
        public ServerHandle detect(MBeanServerExecutor pMBeanServerExecutor) {
            return new NullServerHandle();
        }
    }
}