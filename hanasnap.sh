#!/bin/sh
#
# This script prepares HANA for a backup, then creates a snapshot, then confirms it with HANA
#

# HANA DB things
dbHost=hana01
dbPort=30015
dbInstance=NS1
dbUser=system
dbPassword=Nim123Boli

# Nimble Array things

ARRAY=10.64.32.172
VOL_NAME="hana01-data"

# temp files
function cleanup {
        rm -f ${TMP_FILE}
}
trap cleanup EXIT

TMP_FILE=`mktemp`


# Step 0: get  an API session on the array
# Array credentials are buried here

T=`curl -s -i --insecure -X 'POST' -d '{"data":{"password":"admin", "username":"admin"}}' \
        "https://${ARRAY}:5392/v1/tokens" |\
grep -o '"session_token":[^"]*"[^"]*"' |\
sed -e 's/"session_token": *"//' -e 's/".*$//' `

TOKEN="-H X-Auth-Token:${T}"
PREFIX="${TOKEN} https://${ARRAY}:5392/v1"
CALL='curl -i -s --insecure -X'

# Now get the volunme ID of the volume we care about

VOL_ID=`${CALL} 'GET' ${PREFIX}/volumes?name=${VOL_NAME} | \
	grep -o '"id":[^"]*"[^"]*"' |\
	sed -e 's/"id": *"//' -e 's/".*$//' `

if [ x${VOL_ID} = x ] ; then
	echo NO VOLUME ${VOL_NAME} found on array ${ARRAY} 1>&2
	exit 1
fi

# Step 1: tell HANA to prepare for a snapshot
hdbsql -a -n "$dbHost:$dbPort" -i "$dbInstance" -u "$dbUser" -p "$dbPassword" <<EOF > ${TMP_FILE} 2>&1
BACKUP DATA CREATE SNAPSHOT 
SELECT BACKUP_ID FROM M_BACKUP_CATALOG WHERE STATE_NAME='prepared'
EOF

# was there an error?
if grep -q error ${TMP_FILE} ; then
 (
	echo Error trying to prepare HANA
	cat ${TMP_FILE}
 ) 1>&2 
 exit 10
fi
BACKUP_ID=`tail -3 ${TMP_FILE} | head -1`
echo HANA Backup ID is ${BACKUP_ID}

# Step 2: make a snapshot.  Don't much care about the name
SNAP_NAME=HANAsnap`date '+%s'`
P='{"data":{"name":"'${SNAP_NAME}'", "description":"HANA synchronized backup", "vol_id":"'${VOL_ID}'"}}'
SNAP_ID=`${CALL} 'POST' ${PREFIX}/snapshots -d "$P" | \
	grep -o '"id":[^"]*"[^"]*"' | tail -1 | \
	sed -e 's/"id": *"//' -e 's/".*$//'`

if [ x${SNAP_ID} = x ] ; then
	echo Could not create snapshot ${SNAP_NAME} on ${VOL_NAME} on array ${ARRAY} 1>&2
	hdbsql -a -n "$dbHost:$dbPort" -i "$dbInstance" -u "$dbUser" -p "$dbPassword" <<EOF > ${TMP_FILE} 2>&1
BACKUP DATA CLOSE SNAPSHOT BACKUP_ID ${BACKUP_ID} UNSUCCESSFUL 'HANA BACKUP DEMO failed to create Nimble snapshot'
EOF
	exit 2
fi

echo Nimble SNAP ID IS ${SNAP_ID}

# Step 3: Confirm the snapshot back to HANA
hdbsql -a -n "$dbHost:$dbPort" -i "$dbInstance" -u "$dbUser" -p "$dbPassword" <<EOF > ${TMP_FILE} 2>&1
BACKUP DATA CLOSE SNAPSHOT BACKUP_ID ${BACKUP_ID} SUCCESSFUL '${SNAP_ID}'
EOF

# was there an error?
if grep -q error ${TMP_FILE} ; then
 (
	echo Error trying to confirm snapshot for  HANA
	cat ${TMP_FILE}
 ) 1>&2 
 exit 11
fi
echo Backup Successful
