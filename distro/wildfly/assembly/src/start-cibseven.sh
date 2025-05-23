#!/bin/sh

export JBOSS_HOME="$(dirname "$0")/server/wildfly-${version.wildfly}"

# Define the target file path
FILE="$JBOSS_HOME/modules/org/cibseven/config/main/cibseven-webclient.properties"

# Check if the file already exists
if [ ! -f "$FILE" ]; then
  # Create directory if it doesn't exist
  # mkdir -p "$(dirname "$FILE")"

  # Generate a 155-character alphanumeric random string
  RANDOM_STRING=$(LC_CTYPE=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 155)

  # Write the content to the file
  echo "cibseven.webclient.authentication.jwtSecret=$RANDOM_STRING" > "$FILE"

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

echo "starting CIB seven ${project.version} on Wildfly Application Server ${version.wildfly}";

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
  (sleep 15; $BROWSER "http://localhost:8080/cibseven-welcome/index.html";) &
fi

/bin/sh "$(dirname "$0")/server/wildfly-${version.wildfly}/bin/standalone.sh"
