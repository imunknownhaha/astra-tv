import unittest
from astra_linux.streams import candidates, http_status, failure_message


class StreamsTest(unittest.TestCase):
    def test_xtream_formats_preserve_escaped_login_and_origin(self):
        payload = {'url':'https://example.test:8443/live/a%2Fb/p%26q/42.ts','provider_id':'42'}
        actual = candidates({'kind':'LIVE'},payload)
        self.assertEqual(actual,[payload['url'],payload['url'].replace('.ts','.m3u8')])
        self.assertEqual(candidates({'kind':'LIVE'},payload,preference='hls'),actual[::-1])
        self.assertEqual(candidates({'kind':'LIVE'},payload,preference='ts'),actual[:1])

    def test_m3u_movies_signed_urls_and_catchup_are_not_rewritten(self):
        payload={'url':'https://example.test/channel.ts','provider_id':'42'}
        for item in [{'kind':'MOVIE'},{'kind':'LIVE'}]:
            self.assertEqual(candidates(item,payload),[payload['url']])
        payload['url']='https://example.test/live/u/p/42.ts'
        self.assertEqual(candidates({'kind':'LIVE'},payload,url='https://example.test/archive'),['https://example.test/archive'])
        self.assertEqual(candidates({'kind':'LIVE'},{'url':payload['url']}),[payload['url']])

    def test_error_extraction_never_displays_private_log_text(self):
        private='https://private.test/live/user/secret/42.ts'
        self.assertEqual(http_status('HTTP error 404 Not Found: '+private),404)
        self.assertIsNone(http_status('Failed to open '+private))
        message=failure_message(http_status('HTTP error 403 '+private))
        self.assertIn('403',message)
        for secret in ['private.test','user','secret']:
            self.assertNotIn(secret,message)
