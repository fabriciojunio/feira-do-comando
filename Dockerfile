# Imagem unica, parametrizada pelo modulo.
#
# Tres Dockerfiles quase iguais seriam tres lugares para esquecer de atualizar
# a versao do Java. O argumento MODULO escolhe qual servico empacotar.
#
#   docker build --build-arg MODULO=servico-estoque -t feira-estoque .

FROM maven:3.9-eclipse-temurin-21 AS construcao
ARG MODULO
WORKDIR /app

# Os poms primeiro, e o download depois: dependencia muda muito menos que
# codigo, e assim o cache do Docker aproveita entre builds.
COPY pom.xml .
COPY contratos/pom.xml contratos/
COPY plataforma/pom.xml plataforma/
COPY servico-pedidos/pom.xml servico-pedidos/
COPY servico-estoque/pom.xml servico-estoque/
COPY servico-pagamentos/pom.xml servico-pagamentos/
COPY servico-consulta/pom.xml servico-consulta/
RUN mvn -B -q dependency:go-offline -pl ${MODULO} -am

COPY contratos/src contratos/src
COPY plataforma/src plataforma/src
COPY ${MODULO}/src ${MODULO}/src

# maven.test.skip em vez de skipTests: nem compila o teste, e assim o binario
# do Postgres embutido nao e baixado dentro da imagem.
RUN mvn -B -q package -pl ${MODULO} -am -Dmaven.test.skip=true \
    && cp ${MODULO}/target/*.jar /app/aplicacao.jar

FROM eclipse-temurin:21-jre-alpine AS execucao
WORKDIR /app

# Container que roda como root e um convite: qualquer execucao remota vira
# controle da maquina.
RUN addgroup -S feira && adduser -S feira -G feira
COPY --from=construcao --chown=feira:feira /app/aplicacao.jar aplicacao.jar
USER feira

EXPOSE 8080

# Ajuste para container pequeno: MaxRAMPercentage faz a JVM enxergar o limite
# do container em vez de chutar pelo total da maquina, e o SerialGC gasta
# menos memoria que o G1 quando ha uma CPU so.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q --spider http://localhost:${PORT:-8080}/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "aplicacao.jar"]
