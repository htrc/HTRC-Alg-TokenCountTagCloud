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

# construct algorithm command line arguments
[[ ! -z "$stopwords_url" ]] && OPTIONAL_ARGS="$OPTIONAL_ARGS --stopwords-url $stopwords_url"
[[ ! -z "$corrections_url" ]] && OPTIONAL_ARGS="$OPTIONAL_ARGS --corrections-url $corrections_url"
[[ ! -z "$token_filter" ]] && OPTIONAL_ARGS="$OPTIONAL_ARGS --token-filter $token_filter"
[[ ! -z "$lowercase" && "$lowercase" == "True" ]] && OPTIONAL_ARGS="$OPTIONAL_ARGS --lowercase"
[[ ! -z "max_display" ]] && OPTIONAL_ARGS="$OPTIONAL_ARGS -m $max_display" 

ALG_ARGS=" \
  --dataapi-url $data_api_url \
  -o $output_dir \
  -l $language \
  -c $num_cores \
  $OPTIONAL_ARGS \
  <(sed 1d $workset)
"

ALG_JAVA_OPTS="-J-showversion -J-DlogLevel=DEBUG"
[[ ! -z "$JAVA_MAX_HEAP_SIZE" ]] && ALG_JAVA_OPTS="$ALG_JAVA_OPTS -J$JAVA_MAX_HEAP_SIZE"

### DO NOT MODIFY BELOW THIS LINE ###

eval DATAAPI_TOKEN="$auth_token" \
    $ALG_HOME/bin/$ALG_NAME $ALG_JAVA_OPTS -- $ALG_ARGS &

CHILD_PID="$!"
wait