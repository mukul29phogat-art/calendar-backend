#!/usr/bin/env bash
# Idempotent bootstrap for the calendar-backend AWS surface.
#
# Default target: LocalStack at http://localhost:4566 (dev).
# To target real AWS instead, unset AWS_ENDPOINT_URL and set AWS_PROFILE
# to a profile with sufficient permissions (operator IAM user from a prior run).
#
# Usage:
#   ./infrastructure/localstack/bootstrap.sh           # uses AWS_ENDPOINT_URL or default localhost
#   AWS_ENDPOINT_URL="" ./infrastructure/localstack/bootstrap.sh   # real AWS

set -euo pipefail

PROFILE="${AWS_PROFILE:-childcarewow-calendar}"
ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"
REGION="${AWS_REGION:-us-east-1}"

if [ -n "$ENDPOINT" ]; then
  AWSCMD=(aws --profile "$PROFILE" --region "$REGION" --endpoint-url "$ENDPOINT")
else
  AWSCMD=(aws --profile "$PROFILE" --region "$REGION")
fi

# Locate the operator-policy.json relative to this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
POLICY_FILE="${REPO_ROOT}/infrastructure/iam/operator-policy.json"

if [ ! -f "$POLICY_FILE" ]; then
  echo "ERROR: missing $POLICY_FILE" >&2
  exit 1
fi

# Windows + Git Bash: AWS CLI is a native Windows binary and cannot read
# /c/... paths. Convert to Windows-style for the file:// URI.
if command -v cygpath >/dev/null 2>&1; then
  POLICY_FILE_NATIVE=$(cygpath -w "$POLICY_FILE")
else
  POLICY_FILE_NATIVE="$POLICY_FILE"
fi

echo "==> Endpoint: $ENDPOINT"
echo "==> Profile:  $PROFILE"
echo "==> Region:   $REGION"

echo
echo "==> [1/4] IAM user calendar-operator"
if "${AWSCMD[@]}" iam get-user --user-name calendar-operator >/dev/null 2>&1; then
  echo "    user exists, skipping create"
else
  "${AWSCMD[@]}" iam create-user --user-name calendar-operator >/dev/null
  echo "    created"
fi

KEY_COUNT=$("${AWSCMD[@]}" iam list-access-keys --user-name calendar-operator --query 'length(AccessKeyMetadata)' --output text)
if [ "$KEY_COUNT" = "0" ]; then
  echo "    creating access key (capture this output — only shown once):"
  "${AWSCMD[@]}" iam create-access-key --user-name calendar-operator --query 'AccessKey.{AccessKeyId:AccessKeyId,SecretAccessKey:SecretAccessKey}' --output json
else
  echo "    access key(s) already exist (count=$KEY_COUNT), skipping"
fi

echo
echo "==> [2/4] IAM policy CalendarBackendOperator"
ACCOUNT_ID=$("${AWSCMD[@]}" sts get-caller-identity --query Account --output text)
POLICY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/CalendarBackendOperator"
if "${AWSCMD[@]}" iam get-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1; then
  echo "    policy exists, updating to a new default version"
  EXISTING_VERSIONS=$("${AWSCMD[@]}" iam list-policy-versions --policy-arn "$POLICY_ARN" --query 'Versions[?!IsDefaultVersion].VersionId' --output text)
  for v in $EXISTING_VERSIONS; do
    "${AWSCMD[@]}" iam delete-policy-version --policy-arn "$POLICY_ARN" --version-id "$v" || true
  done
  "${AWSCMD[@]}" iam create-policy-version --policy-arn "$POLICY_ARN" --policy-document "file://${POLICY_FILE_NATIVE}" --set-as-default >/dev/null
else
  "${AWSCMD[@]}" iam create-policy --policy-name CalendarBackendOperator --policy-document "file://${POLICY_FILE_NATIVE}" >/dev/null
  echo "    created"
fi

echo
echo "==> [3/4] Attach CalendarBackendOperator to calendar-operator"
ATTACHED=$("${AWSCMD[@]}" iam list-attached-user-policies --user-name calendar-operator --query "AttachedPolicies[?PolicyArn=='${POLICY_ARN}'].PolicyArn" --output text)
if [ -z "$ATTACHED" ]; then
  "${AWSCMD[@]}" iam attach-user-policy --user-name calendar-operator --policy-arn "$POLICY_ARN"
  echo "    attached"
else
  echo "    already attached"
fi

# Detach any other policies (e.g. leftover IAMFullAccess from real-AWS runs)
OTHER_POLICIES=$("${AWSCMD[@]}" iam list-attached-user-policies --user-name calendar-operator --query "AttachedPolicies[?PolicyArn!='${POLICY_ARN}'].PolicyArn" --output text)
for p in $OTHER_POLICIES; do
  echo "    detaching extra policy: $p"
  "${AWSCMD[@]}" iam detach-user-policy --user-name calendar-operator --policy-arn "$p"
done

echo
echo "==> [4/4] Secrets Manager: 15 placeholder secrets (5 types × 3 envs)"
SECRET_TYPES=(db-credentials supabase-jwt-public-key supabase-service-role firebase-service-account smtp)
ENVS=(dev staging prod)
CREATED=0
EXISTED=0
for env in "${ENVS[@]}"; do
  for type in "${SECRET_TYPES[@]}"; do
    NAME="childcarewow-calendar/${env}/${type}"
    if "${AWSCMD[@]}" secretsmanager describe-secret --secret-id "$NAME" >/dev/null 2>&1; then
      EXISTED=$((EXISTED + 1))
    else
      "${AWSCMD[@]}" secretsmanager create-secret \
        --name "$NAME" \
        --tags "Key=Project,Value=ChildcareWowCalendar" "Key=Env,Value=${env}" \
        --secret-string "{\"placeholder\":\"replace-in-${env}\"}" >/dev/null
      CREATED=$((CREATED + 1))
    fi
  done
done
echo "    created=$CREATED existed=$EXISTED"

echo
echo "==> SKIPPED: GitHub OIDC provider + ChildcareWowCalendarGitHubActions role"
echo "    Real-AWS only — GitHub Actions runners cannot reach LocalStack on localhost."
echo "    Wire this up in Series 11 when the dev env is cut over to real AWS."

echo
echo "✓ Bootstrap complete."
