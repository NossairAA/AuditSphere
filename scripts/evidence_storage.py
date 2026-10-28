"""Minimal signed S3 requests for exact-key maintenance, never lists or deletes buckets."""
import datetime
import hashlib
import hmac
import os
import urllib.parse
import urllib.request
from pathlib import Path

def request(method, key, body=b''):
    dotenv = Path(__file__).resolve().parents[1]/'.env'
    env = dict(l.split('=', 1) for l in dotenv.read_text().splitlines() if '=' in l) if dotenv.exists() else {}
    # The nightly demo reset runs in k3s without .env and passes the cluster values in its environment.
    env.update({k: v for k, v in os.environ.items() if k.startswith('EVIDENCE_S3_')})
    endpoint = env.get('EVIDENCE_S3_ENDPOINT', 'http://127.0.0.1:3900')
    if endpoint == 'http://garage:3900': endpoint = 'http://127.0.0.1:3900'
    host = urllib.parse.urlsplit(endpoint).netloc
    path = '/' + urllib.parse.quote(env['EVIDENCE_S3_BUCKET'], safe='') + '/' + urllib.parse.quote(key, safe='/')
    now = datetime.datetime.now(datetime.timezone.utc)
    timestamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    digest = hashlib.sha256(body).hexdigest()
    headers = f'host:{host}\nx-amz-content-sha256:{digest}\nx-amz-date:{timestamp}\n'
    signed = 'host;x-amz-content-sha256;x-amz-date'
    canonical = '\n'.join([method,path,'',headers,signed,digest])
    region = env.get('EVIDENCE_S3_REGION','garage')
    scope = f'{day}/{region}/s3/aws4_request'
    string = '\n'.join(['AWS4-HMAC-SHA256',timestamp,scope,hashlib.sha256(canonical.encode()).hexdigest()])
    def sign(k,v): return hmac.new(k,v.encode(),hashlib.sha256).digest()
    signing = ('AWS4'+env['EVIDENCE_S3_SECRET_KEY']).encode()
    for part in [day,region,'s3','aws4_request']: signing=sign(signing,part)
    signature = hmac.new(signing,string.encode(),hashlib.sha256).hexdigest()
    auth = f"AWS4-HMAC-SHA256 Credential={env['EVIDENCE_S3_ACCESS_KEY']}/{scope}, SignedHeaders={signed}, Signature={signature}"
    req=urllib.request.Request(endpoint.rstrip('/')+path, data=body if method=='PUT' else None, method=method,
        headers={'Authorization':auth,'x-amz-content-sha256':digest,'x-amz-date':timestamp})
    with urllib.request.urlopen(req,timeout=20) as response: return response.read()
