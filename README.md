# imvaluation

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/imvaluation-1.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- RESTEasy Classic's REST Client ([guide](https://quarkus.io/guides/resteasy-client)): Call REST services
- RESTEasy Classic JSON-B ([guide](https://quarkus.io/guides/rest-json)): JSON-B serialization support for RESTEasy
  Classic
- RESTEasy Classic Multipart ([guide](https://quarkus.io/guides/rest-json#multipart-support)): Multipart support for
  RESTEasy Classic
- RESTEasy Classic ([guide](https://quarkus.io/guides/resteasy)): REST endpoint framework implementing Jakarta REST and
  more
- RESTEasy Classic's REST Client Jackson ([guide](https://quarkus.io/guides/resteasy-client)): Jackson serialization
  support for the REST Client
- SmallRye GraphQL ([guide](https://quarkus.io/guides/smallrye-graphql)): Create GraphQL Endpoints using the code-first
  approach from MicroProfile GraphQL
- RESTEasy Classic Links ([guide](https://quarkus.io/guides/resteasy#links)): Web Links support for RESTEasy Classic.
  Inject web links into response HTTP headers by annotating your endpoint resources.
- SmallRye Stork ([guide](https://quarkus.io/guides/stork)): SmallRye Stork is a dynamic service discovery and selection
  framework for locating and selecting service instances.
- RESTEasy Classic's REST Client JSON-B ([guide](https://quarkus.io/guides/resteasy-client)): JSON-B serialization
  support for the REST client
- RESTEasy Classic's REST Client JAXB ([guide](https://quarkus.io/guides/resteasy-client)): XML serialization support
  for the REST Client
- RESTEasy Classic's REST Client Mutiny support ([guide](https://quarkus.io/guides/resteasy-client)): Enable Mutiny for
  the REST client
- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Validate object properties (field, getter) and
  method parameters for your beans (REST, CDI, Jakarta Persistence)
- RESTEasy Classic Mutiny ([guide](https://quarkus.io/guides/resteasy#reactive)): Mutiny support for RESTEasy Classic
  server
- SmallRye OpenAPI ([guide](https://quarkus.io/guides/openapi-swaggerui)): Document your REST APIs with OpenAPI - comes
  with Swagger UI
- SmallRye GraphQL Client ([guide](https://quarkus.io/guides/smallrye-graphql-client)): Create GraphQL Clients
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplify your persistence code
  for Hibernate ORM via the active record or the repository pattern
- RESTEasy Classic Qute ([guide](https://quarkus.io/guides/qute)): Qute Templating integration for RESTEasy Classic

## Provided Code

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)

[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)

### RESTEasy Client

Invoke different services through REST with JSON

[Related guide section...](https://quarkus.io/guides/resteasy-client)

### RESTEasy JAX-RS

Easily start your RESTful Web Services

[Related guide section...](https://quarkus.io/guides/getting-started#the-jax-rs-resources)

### RESTEasy Qute

Create your web page using Quarkus RESTEasy & Qute

[Related guide section...](https://quarkus.io/guides/qute#type-safe-templates)

### SmallRye GraphQL

Start coding with this Hello GraphQL Query

[Related guide section...](https://quarkus.io/guides/smallrye-graphql)
