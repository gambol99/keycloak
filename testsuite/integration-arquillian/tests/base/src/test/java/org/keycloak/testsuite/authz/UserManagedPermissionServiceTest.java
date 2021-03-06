/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.authz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.NotFoundException;

import org.junit.Test;
import org.keycloak.authorization.client.AuthorizationDeniedException;
import org.keycloak.authorization.client.resource.AuthorizationResource;
import org.keycloak.authorization.client.resource.PolicyResource;
import org.keycloak.authorization.client.resource.ProtectionResource;
import org.keycloak.authorization.client.util.HttpResponseException;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.authorization.AuthorizationRequest;
import org.keycloak.representations.idm.authorization.AuthorizationResponse;
import org.keycloak.representations.idm.authorization.PermissionRequest;
import org.keycloak.representations.idm.authorization.PermissionResponse;
import org.keycloak.representations.idm.authorization.PermissionTicketRepresentation;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.UmaPermissionRepresentation;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.GroupBuilder;
import org.keycloak.testsuite.util.RealmBuilder;
import org.keycloak.testsuite.util.RoleBuilder;
import org.keycloak.testsuite.util.RolesBuilder;
import org.keycloak.testsuite.util.UserBuilder;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class UserManagedPermissionServiceTest extends AbstractResourceServerTest {

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        testRealms.add(RealmBuilder.create().name(REALM_NAME)
                .roles(RolesBuilder.create()
                        .realmRole(RoleBuilder.create().name("uma_authorization").build())
                        .realmRole(RoleBuilder.create().name("uma_protection").build())
                        .realmRole(RoleBuilder.create().name("role_a").build())
                        .realmRole(RoleBuilder.create().name("role_b").build())
                        .realmRole(RoleBuilder.create().name("role_c").build())
                        .realmRole(RoleBuilder.create().name("role_d").build())
                )
                .group(GroupBuilder.create().name("group_a")
                        .subGroups(Arrays.asList(GroupBuilder.create().name("group_b").build()))
                        .build())
                .group(GroupBuilder.create().name("group_c").build())
                .user(UserBuilder.create().username("marta").password("password")
                        .addRoles("uma_authorization", "uma_protection")
                        .role("resource-server-test", "uma_protection"))
                .user(UserBuilder.create().username("alice").password("password")
                        .addRoles("uma_authorization", "uma_protection")
                        .role("resource-server-test", "uma_protection"))
                .user(UserBuilder.create().username("kolo").password("password")
                        .addRoles("role_a")
                        .addGroups("group_a"))
                .client(ClientBuilder.create().clientId("resource-server-test")
                        .secret("secret")
                        .authorizationServicesEnabled(true)
                        .redirectUris("http://localhost/resource-server-test")
                        .defaultRoles("uma_protection")
                        .directAccessGrants()
                        .serviceAccountsEnabled(true))
                .client(ClientBuilder.create().clientId("client-a")
                        .redirectUris("http://localhost/resource-server-test")
                        .publicClient())
                .build());
    }

    @Test
    public void testCreate() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Resource A");
        resource.setOwnerManagedAccess(true);
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        resource = getAuthzClient().protection().resource().create(resource);

        UmaPermissionRepresentation newPermission = new UmaPermissionRepresentation();

        newPermission.setName("Custom User-Managed Permission");
        newPermission.setDescription("Users from specific roles are allowed to access");
        newPermission.addScope("Scope A", "Scope B", "Scope C");
        newPermission.addRole("role_a", "role_b", "role_c", "role_d");
        newPermission.addGroup("/group_a", "/group_a/group_b", "/group_c");
        newPermission.addClient("client-a", "resource-server-test");
        newPermission.setCondition("$evaluation.grant()");

        ProtectionResource protection = getAuthzClient().protection("marta", "password");

        UmaPermissionRepresentation permission = protection.policy(resource.getId()).create(newPermission);

        assertEquals(newPermission.getName(), permission.getName());
        assertEquals(newPermission.getDescription(), permission.getDescription());
        assertTrue(permission.getScopes().containsAll(newPermission.getScopes()));
        assertTrue(permission.getRoles().containsAll(newPermission.getRoles()));
        assertTrue(permission.getGroups().containsAll(newPermission.getGroups()));
        assertTrue(permission.getClients().containsAll(newPermission.getClients()));
        assertEquals(newPermission.getCondition(), permission.getCondition());
    }

    @Test
    public void testUpdate() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Resource A");
        resource.setOwnerManagedAccess(true);
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        resource = getAuthzClient().protection().resource().create(resource);

        UmaPermissionRepresentation permission = new UmaPermissionRepresentation();

        permission.setName("Custom User-Managed Permission");
        permission.setDescription("Users from specific roles are allowed to access");
        permission.addScope("Scope A");
        permission.addRole("role_a");

        ProtectionResource protection = getAuthzClient().protection("marta", "password");

        permission = protection.policy(resource.getId()).create(permission);

        assertEquals(1, getAssociatedPolicies(permission).size());

        permission.setName("Changed");
        permission.setDescription("Changed");

        protection.policy(resource.getId()).update(permission);

        UmaPermissionRepresentation updated = protection.policy(resource.getId()).findById(permission.getId());

        assertEquals(permission.getName(), updated.getName());
        assertEquals(permission.getDescription(), updated.getDescription());

        permission.removeRole("role_a");
        permission.addRole("role_b", "role_c");

        protection.policy(resource.getId()).update(permission);
        assertEquals(1, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getRoles().containsAll(updated.getRoles()));

        permission.addRole("role_d");

        protection.policy(resource.getId()).update(permission);
        assertEquals(1, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getRoles().containsAll(updated.getRoles()));

        permission.addGroup("/group_a/group_b");

        protection.policy(resource.getId()).update(permission);
        assertEquals(2, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getGroups().containsAll(updated.getGroups()));

        permission.addGroup("/group_a");

        protection.policy(resource.getId()).update(permission);
        assertEquals(2, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getGroups().containsAll(updated.getGroups()));

        permission.removeGroup("/group_a/group_b");
        permission.addGroup("/group_c");

        protection.policy(resource.getId()).update(permission);
        assertEquals(2, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getGroups().containsAll(updated.getGroups()));

        permission.addClient("client-a");

        protection.policy(resource.getId()).update(permission);
        assertEquals(3, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getClients().containsAll(updated.getClients()));

        permission.addClient("resource-server-test");

        protection.policy(resource.getId()).update(permission);
        assertEquals(3, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getClients().containsAll(updated.getClients()));

        permission.removeClient("client-a");

        protection.policy(resource.getId()).update(permission);
        assertEquals(3, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertTrue(permission.getClients().containsAll(updated.getClients()));

        permission.setCondition("$evaluation.grant()");

        protection.policy(resource.getId()).update(permission);
        assertEquals(4, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertEquals(permission.getCondition(), updated.getCondition());

        permission.setCondition(null);

        protection.policy(resource.getId()).update(permission);
        assertEquals(3, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertEquals(permission.getCondition(), updated.getCondition());

        permission.setRoles(null);

        protection.policy(resource.getId()).update(permission);
        assertEquals(2, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertEquals(permission.getRoles(), updated.getRoles());

        permission.setClients(null);

        protection.policy(resource.getId()).update(permission);
        assertEquals(1, getAssociatedPolicies(permission).size());
        updated = protection.policy(resource.getId()).findById(permission.getId());

        assertEquals(permission.getClients(), updated.getClients());

        permission.setGroups(null);

        try {
            protection.policy(resource.getId()).update(permission);
            assertEquals(1, getAssociatedPolicies(permission).size());
            fail("Permission must be removed because the last associated policy was removed");
        } catch (NotFoundException ignore) {

        } catch (Exception e) {
            fail("Expected not found");
        }
    }

    @Test
    public void testUserManagedPermission() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Resource A");
        resource.setOwnerManagedAccess(true);
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        resource = getAuthzClient().protection().resource().create(resource);

        UmaPermissionRepresentation permission = new UmaPermissionRepresentation();

        permission.setName("Custom User-Managed Permission");
        permission.setDescription("Users from specific roles are allowed to access");
        permission.addScope("Scope A");
        permission.addRole("role_a");

        ProtectionResource protection = getAuthzClient().protection("marta", "password");

        permission = protection.policy(resource.getId()).create(permission);

        AuthorizationResource authorization = getAuthzClient().authorization("kolo", "password");

        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(resource.getId(), "Scope A");

        AuthorizationResponse authzResponse = authorization.authorize(request);

        assertNotNull(authzResponse);

        permission.removeRole("role_a");
        permission.addRole("role_b");

        protection.policy(resource.getId()).update(permission);

        try {
            authzResponse = authorization.authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }

        try {
            authzResponse = getAuthzClient().authorization("alice", "password").authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }

        permission.addRole("role_a");

        protection.policy(resource.getId()).update(permission);

        authzResponse = authorization.authorize(request);

        assertNotNull(authzResponse);

        protection.policy(resource.getId()).delete(permission.getId());

        try {
            authzResponse = authorization.authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }

        try {
            getAuthzClient().protection("marta", "password").policy(resource.getId()).findById(permission.getId());
            fail("Permission must not exist");
        } catch (Exception e) {
            assertEquals(404, HttpResponseException.class.cast(e.getCause()).getStatusCode());
        }
    }

    @Test
    public void testPermissionInAdditionToUserGrantedPermission() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Resource A");
        resource.setOwnerManagedAccess(true);
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        resource = getAuthzClient().protection().resource().create(resource);

        PermissionResponse ticketResponse = getAuthzClient().protection().permission().create(new PermissionRequest(resource.getId(), "Scope A"));

        AuthorizationRequest request = new AuthorizationRequest();

        request.setTicket(ticketResponse.getTicket());

        try {
            getAuthzClient().authorization("kolo", "password").authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
            assertTrue(e.getMessage().contains("request_submitted"));
        }

        List<PermissionTicketRepresentation> tickets = getAuthzClient().protection().permission().findByResource(resource.getId());

        assertEquals(1, tickets.size());

        PermissionTicketRepresentation ticket = tickets.get(0);

        ticket.setGranted(true);

        getAuthzClient().protection().permission().update(ticket);

        AuthorizationResponse authzResponse = getAuthzClient().authorization("kolo", "password").authorize(request);

        assertNotNull(authzResponse);

        UmaPermissionRepresentation permission = new UmaPermissionRepresentation();

        permission.setName("Custom User-Managed Permission");
        permission.addScope("Scope A");
        permission.addRole("role_a");

        ProtectionResource protection = getAuthzClient().protection("marta", "password");

        permission = protection.policy(resource.getId()).create(permission);

        authzResponse = getAuthzClient().authorization("kolo", "password").authorize(request);

        ticket.setGranted(false);

        getAuthzClient().protection().permission().update(ticket);

        authzResponse = getAuthzClient().authorization("kolo", "password").authorize(request);

        permission = getAuthzClient().protection("marta", "password").policy(resource.getId()).findById(permission.getId());

        assertNotNull(permission);

        permission.removeRole("role_a");
        permission.addRole("role_b");

        getAuthzClient().protection("marta", "password").policy(resource.getId()).update(permission);

        try {
            getAuthzClient().authorization("kolo", "password").authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }

        request = new AuthorizationRequest();

        request.addPermission(resource.getId());

        try {
            getAuthzClient().authorization("kolo", "password").authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }

        getAuthzClient().protection("marta", "password").policy(resource.getId()).delete(permission.getId());

        try {
            getAuthzClient().authorization("kolo", "password").authorize(request);
            fail("User should not have permission");
        } catch (Exception e) {
            assertTrue(AuthorizationDeniedException.class.isInstance(e));
        }
    }

    @Test
    public void testPermissionWithoutScopes() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(UUID.randomUUID().toString());
        resource.setOwner("marta");
        resource.setOwnerManagedAccess(true);
        resource.addScope("Scope A", "Scope B", "Scope C");

        ProtectionResource protection = getAuthzClient().protection();

        resource = protection.resource().create(resource);

        UmaPermissionRepresentation permission = new UmaPermissionRepresentation();

        permission.setName("Custom User-Managed Policy");
        permission.addRole("role_a");

        PolicyResource policy = getAuthzClient().protection("marta", "password").policy(resource.getId());

        permission = policy.create(permission);

        assertEquals(3, permission.getScopes().size());
        assertTrue(Arrays.asList("Scope A", "Scope B", "Scope C").containsAll(permission.getScopes()));

        permission = policy.findById(permission.getId());

        assertTrue(Arrays.asList("Scope A", "Scope B", "Scope C").containsAll(permission.getScopes()));
        assertEquals(3, permission.getScopes().size());

        permission.removeScope("Scope B");

        policy.update(permission);
        permission = policy.findById(permission.getId());

        assertEquals(2, permission.getScopes().size());
        assertTrue(Arrays.asList("Scope A", "Scope C").containsAll(permission.getScopes()));
    }

    @Test
    public void testOnlyResourceOwnerCanManagePolicies() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(UUID.randomUUID().toString());
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        ProtectionResource protection = getAuthzClient().protection();

        resource = protection.resource().create(resource);

        try {
            getAuthzClient().protection("alice", "password").policy(resource.getId()).create(new UmaPermissionRepresentation());
            fail("Error expected");
        } catch (Exception e) {
            assertTrue(HttpResponseException.class.cast(e.getCause()).toString().contains("Only resource onwer can access policies for resource"));
        }
    }

    @Test
    public void testOnlyResourcesWithOwnerManagedAccess() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(UUID.randomUUID().toString());
        resource.setOwner("marta");
        resource.addScope("Scope A", "Scope B", "Scope C");

        ProtectionResource protection = getAuthzClient().protection();

        resource = protection.resource().create(resource);

        try {
            getAuthzClient().protection("marta", "password").policy(resource.getId()).create(new UmaPermissionRepresentation());
            fail("Error expected");
        } catch (Exception e) {
            assertTrue(HttpResponseException.class.cast(e.getCause()).toString().contains("Only resources with owner managed accessed can have policies"));
        }
    }

    @Test
    public void testFindPermission() {
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(UUID.randomUUID().toString());
        resource.setOwner("marta");
        resource.setOwnerManagedAccess(true);
        resource.addScope("Scope A", "Scope B", "Scope C");

        ProtectionResource protection = getAuthzClient().protection();

        resource = protection.resource().create(resource);

        PolicyResource policy = getAuthzClient().protection("marta", "password").policy(resource.getId());

        for (int i = 0; i < 10; i++) {
            UmaPermissionRepresentation permission = new UmaPermissionRepresentation();

            permission.setName("Custom User-Managed Policy " + i);
            permission.addRole("role_a");

            policy.create(permission);
        }

        assertEquals(10, policy.find(null, null, null, null).size());

        List<UmaPermissionRepresentation> byId = policy.find("Custom User-Managed Policy 8", null, null, null);

        assertEquals(1, byId.size());
        assertEquals(byId.get(0).getId(), policy.findById(byId.get(0).getId()).getId());
        assertEquals(10, policy.find(null, "Scope A", null, null).size());
        assertEquals(5, policy.find(null, null, -1, 5).size());
        assertEquals(2, policy.find(null, null, -1, 2).size());
    }

    private List<PolicyRepresentation> getAssociatedPolicies(UmaPermissionRepresentation permission) {
        return getClient(getRealm()).authorization().policies().policy(permission.getId()).associatedPolicies();
    }

}
