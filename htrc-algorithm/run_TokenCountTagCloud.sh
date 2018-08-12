#!/usr/bin/env bash

### ADMINISTRATIVE SETTINGS - DO NOT MODIFY ###

error() {
   [ -z "$1" ] && printf "Error: An unspecified error occurred\n" || printf "Error: $1\n" 1>&2
   [ -z "$2" ] || exit $2
}

trap_with_arg() {
    func="$1" ; shift
    for sig ; do
        trap "$func $sig" "$sig"
    done
}

stop_algorithm() {
    kill -s $1 $CHILD_PID
    sleep 1s
    ps -p $CHILD_PID &>/dev/null && kill -9 $CHILD_PID
}

# sanity checks
[ -z "$HTRC_WORKING_DIR" ] && error "HTRC_WORKING_DIR not set by the Agent" 1
[ -d "$HTRC_WORKING_DIR" ] || error "$HTRC_WORKING_DIR does not exist" 2
[ -z "$HTRC_DEPENDENCY_DIR" ] && error "HTRC_DEPENDENCY_DIR not set by the Agent" 1
[ -d "$HTRC_DEPENDENCY_DIR" ] || error "$HTRC_DEPENDENCY_DIR does not exist" 2

cd "$HTRC_WORKING_DIR"

unset CHILD_PID
trap_with_arg stop_algorithm INT TERM

# use our own Oracle Java 8 version, if available, instead of the system-default Java
[ -d "$HOME/software/java8" ] && export JAVA_HOME="$HOME/software/java8"


### JOB SETTINGS ###

ALG_NAME="token-count-tag-cloud"
ALG_HOME="$HTRC_DEPENDENCY_DIR/$ALG_NAME"
ALG_PROP="TokenCountTagCloud.properties"

[ -d "$ALG_HOME" ] || error "$ALG_HOME does not exist" 2
[ -r "$ALG_PROP" ] || error "$ALG_PROP does not exist" 2

# read the properties file into local variables
while read -r line; do declare "$line"; done < <(sed -rn 's;^([^ =]+)\s?=\s?(.*)$;\1=\2;p' $ALG_PROP)

[ -z "$data_api_url" ] && error "data_api_url not set by the Agent" 1
[ -z "$auth_token" ] && error "auth_token not set by the Agent" 1
[ -z "$output_dir" ] && error "output_dir not set by the Agent" 1
[ -s "$workset" ] || error "$workset does not exist or is empty" 2

# construct algorithm configuration file
cat << EOF > algorithm.conf
$ALG_NAME {
    dataapi-url = "$data_api_url"
    dataapi-token = "$auth_token"
    keystore = $HTRC_DEPENDENCY_DIR/algorithm_certs/algorithm.p12
    keystore-pwd = TDU-2F4-n5k-5ln
    output = $output_dir
    language = $language
    num-cores = $num_cores
    $([[ ! -z "$stopwords_url" ]] && echo "stopwords-url = \"$stopwords_url\"")
    $([[ ! -z "$corrections_url" ]] && echo "corrections-url = \"$corrections_url\"")
    $([[ ! -z "$token_filter" ]] && echo "token-filter = \"\"\"$token_filter\"\"\"")
    $([[ ! -z "$lowercase" && "$lowercase" == "True" ]] && echo "lowercase = true")
    $([[ ! -z "$max_display" ]] && echo "max-display = $max_display")
}
EOF

# the workset file contains a header row; subsequent rows begin with a volume
# id which may or may not be followed by other fields separated with commas;
# remove the header row, and extra fields to provide a list of volume ids to
# the algorithm

ALG_ARGS=" \
  --config algorithm.conf \
  <(sed 1d \"$workset\" | awk -F, '{ print \$1 }') \
"

ALG_JAVA_OPTS="-J-showversion"
[[ ! -z "$JAVA_MAX_HEAP_SIZE" ]] && ALG_JAVA_OPTS="$ALG_JAVA_OPTS -J$JAVA_MAX_HEAP_SIZE"


### DO NOT MODIFY BELOW THIS LINE ###

eval $ALG_HOME/bin/$ALG_NAME $ALG_JAVA_OPTS -- $ALG_ARGS &

CHILD_PID="$!"
wait