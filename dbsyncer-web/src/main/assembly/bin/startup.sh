#!/bin/bash
SCRIPT_DIR=$(cd $(dirname $0);pwd)
APP_DIR=$(cd $SCRIPT_DIR/..;pwd)
# application.properties
CONFIG_PATH=$APP_DIR'/conf/application.properties'
if [ ! -f ${CONFIG_PATH} ]; then
  echo "The conf/application.properties does't exist, please check it first!";
  exit 1
fi

# check process
APP="org.dbsyncer.web.Application" 
PROCESS="`ps -ef|grep java|grep ${APP}|awk '{print $2}'`"
if [[ -n ${PROCESS} ]]; then
  echo "The app already started.";
  exit 1
fi

###########################################################################
# set up environment for Java
#JAVA_HOME=/opt/jdk1.8.0_121
PATH=$JAVA_HOME/bin
# #CLASSPATH=.;$JAVA_HOME/lib;$JAVA_HOME/lib/dt.jar;$JAVA_HOME/lib/tools.jar
SERVER_OPTS='-Xms1024m -Xmx1024m -Xss1m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m'
# set debug model
#SERVER_OPTS="$SERVER_OPTS -Djava.compiler=NONE -Xnoagent -Xdebug -Xrunjdwp:transport=dt_socket,address=15005,server=y,suspend=n"
# set jmxremote args
JMXREMOTE_CONFIG_PATH="$APP_DIR/conf"
JMXREMOTE_HOSTNAME="-Djava.rmi.server.hostname=$HOST"
JMXREMOTE_PORT="-Dcom.sun.management.jmxremote.port=15099"
JMXREMOTE_SSL="-Dcom.sun.management.jmxremote.ssl=false"
JMXREMOTE_AUTH="-Dcom.sun.management.jmxremote.authenticate=true"
JMXREMOTE_ACCESS="-Dcom.sun.management.jmxremote.access.file=$JMXREMOTE_CONFIG_PATH/jmxremote.access"
JMXREMOTE_PASSWORD="-Dcom.sun.management.jmxremote.password.file=$JMXREMOTE_CONFIG_PATH/jmxremote.password"
# set jmxremote model
#SERVER_OPTS="$SERVER_OPTS $JMXREMOTE_HOSTNAME $JMXREMOTE_PORT $JMXREMOTE_SSL $JMXREMOTE_AUTH $JMXREMOTE_ACCESS $JMXREMOTE_PASSWORD"
# set IPv4
#SERVER_OPTS="$SERVER_OPTS -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses"
echo $SERVER_OPTS
# execute commond
java $SERVER_OPTS \
-Dfile.encoding=utf8 \
-Djava.ext.dirs=$APP_DIR/lib \
-Dspring.config.location=$CONFIG_PATH \
$APP > /dev/null & echo $! > $APP_DIR/tmp.pid
echo 'Start successfully!';
exit 1