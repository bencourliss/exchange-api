# Adds a workload
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "My Pattern", "description": "blah blah", "public": true,
  "workloads": [
    {
      "workloadUrl": "https://bluehorizon.network/workloads/netspeed",
      "workloadOrgid": "'$EXCHANGE_ORG'",
      "workloadArch": "amd64",
      "workloadVersions": [
        {
          "version": "1.0.0",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "a",
          "priority": {},
          "upgradePolicy": {}
        }
      ],
      "dataVerification": {}
    }
  ],
  "agreementProtocols": [{ "name": "Basic" }]
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/patterns/p1 | $parse

#      "nodeHealth": { "missing_heartbeat_interval": 600, "check_agreement_status": 120 }
