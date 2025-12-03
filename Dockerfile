FROM openjdk:17.0.1-jdk-slim
COPY cb-discussion-service-0.0.1-SNAPSHOT.jar /opt/
CMD ["/bin/bash", "-c", "java -XX:+PrintFlagsFinal $JAVA_OPTIONS -XX:+UnlockExperimentalVMOptions -jar /opt/cb-discussion-service-0.0.1-SNAPSHOT.jar"]
