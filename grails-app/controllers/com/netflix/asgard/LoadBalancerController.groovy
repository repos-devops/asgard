/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck
import com.amazonaws.services.elasticloadbalancing.model.Listener
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import grails.converters.JSON
import grails.converters.XML

class LoadBalancerController {

    def applicationService
    def awsEc2Service
    def awsLoadBalancerService
    def awsAutoScalingService
    def stackService

    // the delete, save and update actions only accept POST requests
    def static allowedMethods = [
            delete: 'POST', save: 'POST', update: 'POST', addListener: 'POST', removeListener: 'POST'
    ]

    def index = { redirect(action:list, params:params) }

    def list = {
        UserContext userContext = UserContext.of(request)
        Set<String> appNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
        Collection<LoadBalancerDescription> loadBalancers = awsLoadBalancerService.getLoadBalancers(userContext)
        if (appNames) {
            loadBalancers = loadBalancers.findAll { LoadBalancerDescription loadBalancer ->
                appNames.contains(Relationships.appNameFromLoadBalancerName(loadBalancer.loadBalancerName))
            }
        }
        loadBalancers = loadBalancers.sort { it.loadBalancerName.toLowerCase() }
        withFormat {
            html { [loadbalancers: loadBalancers, appNames: appNames] }
            xml { new XML(loadBalancers).render(response) }
            json { new JSON(loadBalancers).render(response) }
        }
    }

    def show = {
        String name = params.name ?: params.id
        UserContext userContext = UserContext.of(request)
        LoadBalancerDescription lb = awsLoadBalancerService.getLoadBalancer(userContext, name)
        if (!lb) {
            Requests.renderNotFound('Load Balancer', name, this)
        } else {
            def appName = awsLoadBalancerService.getAppNameForLoadBalancer(name)
            //println "Show LB: ${name} for app: ${appName}"
            List<AutoScalingGroup> groups =
                    awsAutoScalingService.getAutoScalingGroupsForLB(userContext, name).sort { it.autoScalingGroupName }
            List<String> clusterNames =
                    groups.collect { Relationships.clusterFromGroupName(it.autoScalingGroupName) }.unique()
            def details = [
                    'loadBalancer': lb,
                    'app': applicationService.getRegisteredApplication(userContext, appName),
                    'clusters': clusterNames,
                    'groups': groups,
                    'instanceStates': awsLoadBalancerService.getInstanceHealth(userContext, name)
            ]
            withFormat {
                html { return details }
                xml { new XML(details).render(response) }
                json { new JSON(details).render(response) }
            }
        }
    }

    def create = {
        UserContext userContext = UserContext.of(request)
        [
            'applications' : applicationService.getRegisteredApplicationsForLoadBalancer(userContext),
            'stacks' : stackService.getStacks(userContext),
            'zoneList' : awsEc2Service.getAvailabilityZones(userContext)
        ]
    }

    def save = { LoadBalancerCreateCommand cmd ->
        if (cmd.hasErrors()) {
            chain(action:create, model:[cmd:cmd], params:params) // Use chain to pass both the errors and the params
        } else {

            // Load Balancer name
            def appName = params.appName
            def stack = params.newStack ?: params.stack
            def detail = params.detail
            def lbName = Relationships.buildLoadBalancerName(appName, stack, detail)

            UserContext userContext = UserContext.of(request)
            List<String> zoneList = Requests.ensureList(params.selectedZones)
            try {
                def listener1 = new Listener()
                        .withProtocol(params.protocol1)
                        .withLoadBalancerPort(params.lbPort1.toInteger()).withInstancePort(params.instancePort1.toInteger())
                List<Listener> listeners = [listener1]
                if (params.protocol2) {
                    listeners.add(new Listener()
                            .withProtocol(params.protocol2)
                            .withLoadBalancerPort(params.lbPort2.toInteger()).withInstancePort(params.instancePort2.toInteger()))
                }
                awsLoadBalancerService.createLoadBalancer(userContext, lbName, zoneList, listeners)
                updateHealthCheck(userContext, lbName, params)
                flash.message = "Load Balancer '${lbName}' has been created. You should now file two tickets: " +
                        "one Eng Tools Jira ticket for Security Group ingress access, " +
                        "and one Service Now ticket for a new public CNAME."
                redirect(action:show, params:[name:lbName])
            } catch (Exception e) {
                flash.message = "Could not create Load Balancer: ${e}"
                chain(action: create, model: [cmd: cmd], params: params)
            }
        }
    }

    def delete = {
        UserContext userContext = UserContext.of(request)
        String name = params.name
        try {
            awsLoadBalancerService.removeLoadBalancer(userContext, params.name)
            flash.message = "Load Balancer '${name}' has been deleted."
            redirect(action: 'result')
        } catch (Exception e) {
            flash.message = "Could not delete Load Balancer: ${e}"
            redirect(action: show, params:[id: name])
        }
    }

    def edit = {
        String name = params.name ?: params.id
        UserContext userContext = UserContext.of(request)
        ['loadBalancer' : awsLoadBalancerService.getLoadBalancer(userContext, name),
         'zoneList' : awsEc2Service.getAvailabilityZones(userContext) ]
    }

    def update = {
        String name = params.name
        UserContext userContext = UserContext.of(request)
        def lb = awsLoadBalancerService.getLoadBalancer(userContext, name)

        // Zones

        List<String> origZones = lb.availabilityZones.sort()
        List<String> zoneList = Requests.ensureList(params.selectedZones)
        zoneList = zoneList.sort()

        List<String> removedZones = origZones - zoneList
        List<String> addedZones = zoneList - origZones

        if (addedZones.size() > 0) {
            awsLoadBalancerService.addZones(userContext, name, addedZones)
            def msg = "Added zone${addedZones.size() == 1 ? "" : "s"} $addedZones to load balancer. "
            flash.message = flash.message ? flash.message + msg : msg
        }
        if (removedZones.size() > 0) {
            awsLoadBalancerService.removeZones(userContext, name, removedZones)
            def msg = "Removed zone${removedZones.size() == 1 ? "" : "s"} $removedZones from load balancer. "
            flash.message = flash.message ? flash.message + msg : msg
        }

        // Health check
        Boolean healthCheckUpdated = false
        try {
            updateHealthCheck(userContext, name, params)
            healthCheckUpdated = true
        }
        catch (AmazonServiceException ase) {
            def msg = "Failed to update health check: ${ase} "
            flash.message = flash.message ? flash.message + msg : msg
        }

        if (healthCheckUpdated) {
            def msg  = "Load Balancer '${name}' health check has been updated. "
            flash.message = flash.message ? flash.message + msg : msg
        }
        redirect(action:show, params:[name:name])
    }

    // Used by both create and update to set the health check from page params
    private void updateHealthCheck(userContext, name, params) {
        awsLoadBalancerService.configureHealthCheck(userContext, name,
            new HealthCheck().withTarget(params.target)
                    .withInterval(params.interval.toInteger()).withTimeout(params.timeout.toInteger())
                    .withUnhealthyThreshold(params.unhealthy.toInteger()).withHealthyThreshold(params.healthy.toInteger()))
    }

    def prepareListener = {
        String loadBalancer = params.id ?: params.name
        String protocol = params.protocol
        String lbPort = params.lbPort
        String instancePort = params.instancePort
        [loadBalancer: loadBalancer, protocol: protocol, lbPort: lbPort, instancePort: instancePort]
    }

    def addListener = { AddListenerCommand cmd ->
        if (cmd.hasErrors()) {
            chain(action: 'prepareListener', model: [cmd:cmd], params: params)
        } else {
            UserContext userContext = UserContext.of(request)
            Listener listener = new Listener(protocol: cmd.protocol, loadBalancerPort: cmd.lbPort,
                    instancePort: cmd.instancePort)
            try {
                awsLoadBalancerService.addListeners(userContext, cmd.name, [listener])
                flash.message = "Listener has been added to port ${listener.loadBalancerPort}."
                redirect(action: 'show', params: [id: cmd.name])
            } catch (Exception e) {
                flash.message = "Could not add listener: ${e}"
                chain(action: 'prepareListener', model: [cmd: cmd], params: params)
            }
        }
    }

    def removeListener = { RemoveListenerCommand cmd ->
        if (cmd.hasErrors()) {
            chain(action: 'show', model: [cmd:cmd], params: params)
        } else {
            UserContext userContext = UserContext.of(request)
            try {
                awsLoadBalancerService.removeListeners(userContext, cmd.name, [cmd.lbPort])
                flash.message = "Listener on port ${cmd.lbPort} has been removed."
                redirect(action: 'show', params: [id: cmd.name])
            } catch (Exception e) {
                flash.message = "Could not remove listener on port ${cmd.lbPort}: ${e}"
                chain(action: 'show', model: [cmd: cmd], params: params)
            }
        }
    }

    def result = { render view: '/common/result' }
}

class LoadBalancerCreateCommand {

    def applicationService

    String appName
    String stack
    String newStack
    String detail

    String protocol1
    Integer lbPort1
    Integer instancePort1

    String protocol2
    Integer lbPort2
    Integer instancePort2

    String target
    Integer interval
    Integer timeout
    Integer unhealthy
    Integer healthy

    static constraints = {

        appName(nullable: false, blank: false, validator: { value, command->
            UserContext userContext = UserContext.of(Requests.request)
            if (!Relationships.checkStrictName(value)) {
                return "application.name.illegalChar"
            }
            if (Relationships.usesReservedFormat(value)) {
                return "name.usesReservedFormat"
            }
            if (!command.applicationService.getRegisteredApplicationForLoadBalancer(userContext, value)) {
                return "application.name.nonexistent"
            }
            if ("${value}-${command.stack}${command.newStack}-${command.detail}".length() > Relationships.GROUP_NAME_MAX_LENGTH) {
                return "The complete load balancer name cannot exceed ${Relationships.GROUP_NAME_MAX_LENGTH} characters"
            }
        })

        stack(validator: { value, command->
            if (value && !Relationships.checkName(value)) {
                return "The stack must be empty or consist of alphanumeric characters"
            }
            if (Relationships.usesReservedFormat(value)) {
                return "name.usesReservedFormat"
            }
        })

        newStack(validator: { value, command->
            if (value && !Relationships.checkName(value)) {
                return "stack.illegalChar"
            }
            if (Relationships.usesReservedFormat(value)) {
                return "name.usesReservedFormat"
            }
            if (value && command.stack) {
                return "stack.matchesNewStack"
            }
        })

        detail(validator: { value, command->
            if (value && !Relationships.checkDetail(value)) {
                return "The detail must be empty or consist of alphanumeric characters and hyphens"
            }
            if (Relationships.usesReservedFormat(value)) {
                return "name.usesReservedFormat"
            }
        })

        protocol1(nullable: false, blank: false)
        lbPort1(nullable: false, range: 0..65535)
        instancePort1(nullable: false, range: 0..65535)

        protocol2(validator: { value, command->
            if (value && (!command.lbPort2 || !command.instancePort2) ) {
                return "Please enter port numbers for the second protocol"
            }
        })
        lbPort2(range: 0..65535)
        instancePort2(range: 0..65535)

        target(nullable: false, blank: false)
        interval(nullable: false, range: 0..1000)
        timeout(nullable: false, range: 0..1000)
        unhealthy(nullable: false, range: 0..1000)
        healthy(nullable: false, range: 0..1000)
    }
}

class AddListenerCommand {
    String name
    String protocol
    Integer lbPort
    Integer instancePort

    static constraints = {
        name(nullable: false, blank: false)
        protocol(nullable: false, blank: false)
        lbPort(nullable: false, range: 0..65535)
        instancePort(nullable: false, range: 0..65535)
    }
}

class RemoveListenerCommand {
    String name
    Integer lbPort

    static constraints = {
        name(nullable: false, blank: false)
        lbPort(nullable: false, range: 0..65535)
    }
}
