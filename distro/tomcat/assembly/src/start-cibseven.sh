#!/bin/sh

export CATALINA_HOME="$(dirname "$0")/server/apache-tomcat-${version.tomcat}"

# Define the target file path
FILE="$CATALINA_HOME/lib/cibseven-webclient.properties"

# Check if the file already exists
if [ ! -f "$FILE" ]; then
  # Create directory if it doesn't exist
  # mkdir -p "$(dirname "$FILE")"

  # Generate a 155-character alphanumeric random string
  RANDOM_STRING=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 155)

  # Write the content to the file
  echo "authentication.jwtSecret=$RANDOM_STRING" > "$FILE"

  echo "File \"$FILE\" created with random jwtSecret."
else
  echo "File \"$FILE\" already exists. No changes made."
fi

UNAME=`which uname`
if [ -n "$UNAME" -a "`$UNAME`" = "Darwin" ]
then
	BROWSERS="open"
else
	BROWSERS="xdg-open gnome-www-browser x-www-browser firefox chromium chromium-browser google-chrome"
fi

echo "starting CIB seven ${project.version} on Tomcat Application Server";

if [ -z "$BROWSER" ]; then
  for executable in $BROWSERS; do
    BROWSER=`which $executable 2> /dev/null`
    if [ -n "$BROWSER" ]; then
      break;
    fi
  done
fi

if [ -z "$BROWSER" ]; then
  (sleep 15; echo -e "We are sorry... We tried all we could do but we couldn't locate your default browser... \nIf you want to see our default website please open your browser and insert this URL:\nhttp://localhost:8080/cibseven-welcome/index.html";) &
else
  (sleep 5; $BROWSER "http://localhost:8080/cibseven-welcome/index.html";) &
fi

/bin/sh "$(dirname "$0")/server/apache-tomcat-${version.tomcat}/bin/startup.sh"
