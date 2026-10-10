ARG MAVEN_IMAGE
ARG JRE_IMAGE
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
COPY backend/.mvn/ ./.mvn/
COPY backend/src/main/ ./src/main/
RUN mvn -B '-Dcooker.t6.enabled=false' '-Dmaven.test.skip=true' package
FROM ${JRE_IMAGE}
WORKDIR /opt/cooker
COPY --from=build --chown=10001:10001 /build/target/cooker-backend-0.0.1-SNAPSHOT.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
