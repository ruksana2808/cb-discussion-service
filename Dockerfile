FROM openjdk:17.0.1-jdk-slim

RUN useradd -ms /bin/bash appuser

COPY cb-discussion-service-0.0.1-SNAPSHOT.jar /opt/

RUN chown -R appuser:appuser /opt
USER appuser
WORKDIR /opt

CMD ["/bin/bash", "-c", "java -XX:+PrintFlagsFinal $JAVA_OPTIONS -XX:+UnlockExperimentalVMOptions -jar /opt/cb-discussion-service-0.0.1-SNAPSHOT.jar"]
