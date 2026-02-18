# CIB seven SCIM Identity Provider Plugin

This plugin provides integration with SCIM 2.0 (System for Cross-domain Identity Management) compliant identity providers such as Azure AD, Okta, OneLogin, and others.

## Features

- Query users and groups from SCIM 2.0 servers
- Support for multiple authentication methods:
  - Bearer Token
  - Basic Authentication
  - OAuth2 Client Credentials
- Configurable attribute mappings for users and groups
- Pagination support
- Authorization checks
- SSL/TLS configuration with support for self-signed certificates

## Configuration

### Basic Configuration with Bearer Token

```xml
<plugin>
  <class>org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin</class>
  <properties>
    <property name="serverUrl">https://scim.example.com</property>
    <property name="authenticationType">bearer</property>
    <property name="bearerToken">your-access-token</property>
  </properties>
</plugin>
```

### Configuration with Basic Authentication

```xml
<plugin>
  <class>org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin</class>
  <properties>
    <property name="serverUrl">https://scim.example.com</property>
    <property name="authenticationType">basic</property>
    <property name="username">admin</property>
    <property name="password">password</property>
  </properties>
</plugin>
```

### Configuration with OAuth2

```xml
<plugin>
  <class>org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin</class>
  <properties>
    <property name="serverUrl">https://scim.example.com</property>
    <property name="authenticationType">oauth2</property>
    <property name="oauth2TokenUrl">https://auth.example.com/oauth/token</property>
    <property name="oauth2ClientId">your-client-id</property>
    <property name="oauth2ClientSecret">your-client-secret</property>
    <property name="oauth2Scope">scim.read</property>
  </properties>
</plugin>
```

## Configuration Options

### Server Settings
- `serverUrl` (required): Base URL of the SCIM server
- `scimVersion`: SCIM version (default: "2.0")
- `usersEndpoint`: Users endpoint path (default: "/Users")
- `groupsEndpoint`: Groups endpoint path (default: "/Groups")

### Authentication Settings
- `authenticationType`: Authentication type - "bearer", "basic", or "oauth2" (default: "bearer")
- `bearerToken`: Bearer token for authentication
- `username`: Username for basic authentication
- `password`: Password for basic authentication
- `oauth2TokenUrl`: OAuth2 token endpoint URL
- `oauth2ClientId`: OAuth2 client ID
- `oauth2ClientSecret`: OAuth2 client secret
- `oauth2Scope`: OAuth2 scope

### User Attribute Mapping
- `userIdAttribute`: SCIM attribute for user ID (default: "userName")
- `userFirstnameAttribute`: SCIM attribute for first name (default: "name.givenName")
- `userLastnameAttribute`: SCIM attribute for last name (default: "name.familyName")
- `userEmailAttribute`: SCIM attribute for email (default: "emails[type eq \"work\"].value")

### Group Attribute Mapping
- `groupIdAttribute`: SCIM attribute for group ID (default: "externalId")
- `groupNameAttribute`: SCIM attribute for group name (default: "displayName")
- `groupMemberAttribute`: SCIM attribute for group members (default: "members")

### Connection Settings
- `connectionTimeout`: Connection timeout in milliseconds (default: 30000)
- `socketTimeout`: Socket timeout in milliseconds (default: 30000)
- `maxConnections`: Maximum number of connections (default: 100)

### SSL/TLS Settings
- `useSsl`: Use SSL/TLS (default: true)
- `acceptUntrustedCertificates`: Accept self-signed certificates (default: false, use with caution)

### Other Settings
- `authorizationCheckEnabled`: Enable authorization checks (default: true)
- `pageSize`: Number of results per page (default: 100)

## Custom Attribute Mappings

If your SCIM server uses custom attribute names, you can map them accordingly:

```xml
<plugin>
  <class>org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin</class>
  <properties>
    <property name="serverUrl">https://scim.example.com</property>
    <property name="bearerToken">your-token</property>
    
    <!-- Custom attribute mappings -->
    <property name="userIdAttribute">id</property>
    <property name="userFirstnameAttribute">givenName</property>
    <property name="userLastnameAttribute">surname</property>
    <property name="userEmailAttribute">primaryEmail</property>
    
    <property name="groupIdAttribute">id</property>
    <property name="groupNameAttribute">displayName</property>
  </properties>
</plugin>
```

## Limitations

- This is a **read-only** identity provider. User and group management must be done through your SCIM server.
- Password validation via `checkPassword()` is not supported as SCIM is a provisioning protocol, not an authentication protocol.
- Multi-tenancy is not supported.

## Supported SCIM Providers

This plugin should work with any SCIM 2.0 compliant identity provider, including:
- Azure Active Directory
- Okta
- OneLogin
- Google Workspace
- Ping Identity
- And other SCIM 2.0 compliant providers

## Security Considerations

- Always use SSL/TLS in production (`useSsl=true`)
- Only use `acceptUntrustedCertificates=true` for development/testing
- Store credentials securely and consider using environment variables or secure vaults
- Regularly rotate OAuth2 client secrets and access tokens
- Monitor and audit access to the SCIM endpoints

## Troubleshooting

### Enable Debug Logging

To see detailed SCIM queries and responses, enable debug logging for the SCIM plugin:

```xml
<logger name="org.cibseven.bpm.identity.impl.scim" level="DEBUG" />
```

### Common Issues

1. **401 Unauthorized**: Check your authentication credentials and ensure they are valid
2. **SSL Certificate Errors**: If using self-signed certificates, set `acceptUntrustedCertificates=true` (not recommended for production)
3. **Empty Result Sets**: Verify your attribute mappings match your SCIM server's schema
4. **Timeout Errors**: Increase `connectionTimeout` and `socketTimeout` values

## License

Apache License 2.0
