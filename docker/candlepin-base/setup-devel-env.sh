#!/bin/sh
#
# Sets a system up for a candlepin development environment (minus a db,
# handled separately), and an initial clone of candlepin.

set -ve

source /root/dockerlib.sh

export JAVA_VERSION=1.8.0
export JAVA_HOME=/usr/lib/jvm/java-$JAVA_VERSION

# Install & configure dev environment
yum install -y epel-release

PACKAGES=(
    gcc
    gettext
    git
    hostname
    java-$JAVA_VERSION-openjdk-devel
    java-1.6.0-openjdk-devel  # FIXME see if still necessary
    #java-1.7.0-openjdk-devel
    java-1.8.0-openjdk-devel  # FIXME redundant?
    libxml2-python
    liquibase
    mariadb
    mysql-connector-java
    openssl
    postgresql
    postgresql-jdbc
    python-pip
    qpid-proton-c
    qpid-proton-c-devel
    rpmlint
    rsyslog
    tig
    tmux
    tomcat
    vim-enhanced
    wget
)

yum install -y ${PACKAGES[@]}

# pg_isready is used to check if the postgres server is up
# it is not included in postgresql versions < 9.3.0.
# therefore we must build it
if [[ $(printf "$(psql --version | awk '{print $3}')\n9.3.0" | sort -V | head -1) != '9.3.0' ]]; then
  git clone https://github.com/postgres/postgres.git /root/postgres
  cd /root/postgres
  yum install -y bison bison-devel flex flex-devel readline-devel zlib-devel openssl-devel wget
  ./configure
  # just builds the scripts folder, use installed postgres for everything else
  make install src/bin/scripts/
  # only need pg_isready for now
  cp /usr/local/pgsql/bin/pg_isready /usr/local/bin/
  # cleanup
  cd /
  rm -rf /root/postgres
fi

# set up oracle client tools
mkdir -p /root/oracle
curl http://auto-services.usersys.redhat.com/rhsm/oracle-11.2.0.4.0-1.tar.gz > /root/oracle/oracle.tar.gz
cd /root/oracle
tar xvf oracle.tar.gz
yum -y localinstall oracle-instantclient*.rpm
ojdbcdir='/root/.m2/repository/com/oracle/ojdbc6/11.2.0'
mkdir -p $ojdbcdir
mv -f ojdbc6.jar $ojdbcdir/ojdbc6-11.2.0.jar
cd -
ln -s /usr/lib/oracle/11.2/client64/bin/sqlplus /usr/local/bin/sqlplus
#export LD_LIBRARY_PATH=/usr/lib/oracle/11.2/client64/lib:$LD_LIBRARY_PATH
echo 'export LD_LIBRARY_PATH=/usr/lib/oracle/11.2/client64/lib:$LD_LIBRARY_PATH' >> /etc/profile.d/oracle_profile.sh
rm -rf /root/oracle

# Setup for autoconf:
mkdir /etc/candlepin
echo "# AUTOGENERATED" > /etc/candlepin/candlepin.conf

cat > /root/.bashrc <<BASHRC
if [ -f /etc/bashrc ]; then
  . /etc/bashrc
fi

export HOME=/root
export JAVA_HOME=/usr/lib/jvm/java-$JAVA_VERSION
BASHRC

git clone https://github.com/candlepin/candlepin.git /candlepin
cd /candlepin

# Setup and install rvm, ruby and pals
gpg --keyserver hkp://keys.gnupg.net --recv-keys 409B6B1796C275462A1703113804BB82D39DC0E3
# turning off verbose mode, rvm is nuts with this
set +v
curl -sSL https://get.rvm.io | bash -s stable  # CURL TO BASH!!! THE BEST THE BEST THE BEST
source /etc/profile.d/rvm.sh


rvm install 2.0.0
rvm use --default 2.0.0
set -v

# Install all ruby deps
gem install bundler
bundle install

# Installs all Java deps into the image, big time saver
# We run checkstyle explicitly here so it'll pull down its deps as well
buildr artifacts
buildr checkstyle || true

cd /
rm -rf /candlepin
cleanup_env
