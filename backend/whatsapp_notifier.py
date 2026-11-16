import os
import re

import requests


class WhatsAppConfigurationError(ValueError):
    pass


class WhatsAppDeliveryError(RuntimeError):
    pass


def _required_setting(name):
    value = os.environ.get(name, '').strip()
    if not value:
        raise WhatsAppConfigurationError(f'{name} is not configured.')
    return value


def validate_whatsapp_configuration():
    _required_setting('WHATSAPP_ACCESS_TOKEN')
    phone_number_id = _required_setting('WHATSAPP_PHONE_NUMBER_ID')
    recipient = _required_setting('WHATSAPP_TO')
    _required_setting('WHATSAPP_TEMPLATE_NAME')
    api_version = _required_setting('WHATSAPP_GRAPH_API_VERSION')

    if not re.fullmatch(r'v\d+\.\d+', api_version):
        raise WhatsAppConfigurationError('WHATSAPP_GRAPH_API_VERSION must look like vNN.N.')
    if not re.fullmatch(r'\d+', phone_number_id):
        raise WhatsAppConfigurationError('WHATSAPP_PHONE_NUMBER_ID must contain only digits.')
    if not re.fullmatch(r'\+?[1-9]\d{7,14}', recipient):
        raise WhatsAppConfigurationError('WHATSAPP_TO must be a valid E.164 phone number.')


def send_whatsapp_alert(risk_level, message):
    access_token = _required_setting('WHATSAPP_ACCESS_TOKEN')
    phone_number_id = _required_setting('WHATSAPP_PHONE_NUMBER_ID')
    recipient = _required_setting('WHATSAPP_TO')
    template_name = _required_setting('WHATSAPP_TEMPLATE_NAME')
    language = os.environ.get('WHATSAPP_TEMPLATE_LANGUAGE', 'en').strip() or 'en'
    api_version = _required_setting('WHATSAPP_GRAPH_API_VERSION')
    validate_whatsapp_configuration()
    recipient = recipient.lstrip('+')

    response = requests.post(
        f'https://graph.facebook.com/{api_version}/{phone_number_id}/messages',
        headers={'Authorization': f'Bearer {access_token}'},
        json={
            'messaging_product': 'whatsapp',
            'to': recipient,
            'type': 'template',
            'template': {
                'name': template_name,
                'language': {'code': language},
                'components': [{
                    'type': 'body',
                    'parameters': [
                        {'type': 'text', 'text': str(risk_level)},
                        {'type': 'text', 'text': str(message)[:1024]},
                    ],
                }],
            },
        },
        timeout=8,
    )
    response.raise_for_status()

    try:
        payload = response.json()
    except ValueError as error:
        raise WhatsAppDeliveryError('WhatsApp returned an invalid JSON response.') from error

    messages = payload.get('messages') if isinstance(payload, dict) else None
    if not isinstance(messages, list) or not messages or not isinstance(messages[0], dict) or not messages[0].get('id'):
        raise WhatsAppDeliveryError('WhatsApp did not confirm that the alert was accepted.')
    return messages[0]['id']
