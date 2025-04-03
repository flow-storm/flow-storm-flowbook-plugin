.PHONY: lint clean

clean:
	clj -T:build clean

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src

flow-storm-async-flow-plugin.jar:
	clj -T:build jar

install: flow-storm-async-flow-plugin.jar
	mvn install:install-file -Dfile=target/flow-storm-async-flow-plugin.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-async-flow-plugin/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/flow-storm-async-flow-plugin.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-async-flow-plugin/pom.xml -Durl=https://clojars.org/repo


