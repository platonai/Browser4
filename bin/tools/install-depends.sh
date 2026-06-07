#!/usr/bin/env bash

install_utils() {
  if ! command -v java &> /dev/null
  then
    sudo apt install wget vim default-jdk maven git curl
  fi

  if ! command -v mvn &> /dev/null
  then
      sudo apt install maven
  fi

  if ! command -v curl &> /dev/null
  then
      sudo apt install curl
  fi
}

start_dependent_daemon() {
  # "Failed to connect to bus: No such file or directory" on clean WSL
  # https://github.com/Microsoft/WSL/issues/2941
  uname=$(uname -a)
  if [[ "$uname" == *"microsoft"* ]]
  then
    status=$(service dbus status)
    if [[ "$status" != *"running"* ]]
    then
      sudo service dbus start
    fi
  fi
}

install_chrome() {
    echo "Installing latest stable google-chrome"

    wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
    sudo dpkg -i google-chrome*.deb
    sudo apt-get install -f

    google-chrome -version
}

install_powsershell() {
    if command -v pwsh &> /dev/null
    then
        return
    fi

    echo "PowerShell (pwsh) is not installed. Installing PowerShell..."

    curl -fsSL https://aka.ms/install-powershell.sh | bash
}

cd /tmp/ || exit

install_utils

start_dependent_daemon

# find out chrome version
CHROME_VERSION="$(google-chrome -version | head -n1 | awk -F '[. ]' '{print $3}')"

if [[ "$CHROME_VERSION" == "" ]]; then
    install_chrome
fi

cd - || exit
