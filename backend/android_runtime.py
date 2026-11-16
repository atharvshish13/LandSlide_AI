import json
import os
import threading


_server = None
_server_lock = threading.Lock()
_server_thread = None

_CONFIG_KEYS = {
    'whatsapp_access_token': 'WHATSAPP_ACCESS_TOKEN',
    'whatsapp_phone_number_id': 'WHATSAPP_PHONE_NUMBER_ID',
    'whatsapp_to': 'WHATSAPP_TO',
    'whatsapp_template_name': 'WHATSAPP_TEMPLATE_NAME',
    'whatsapp_template_language': 'WHATSAPP_TEMPLATE_LANGUAGE',
    'whatsapp_graph_api_version': 'WHATSAPP_GRAPH_API_VERSION',
    'weatherstack_api_key': 'WEATHERSTACK_API_KEY',
    'gemini_api_key': 'GEMINI_API_KEY',
}

def warm_model():
    from model.android_forest import load_forest
    load_forest()
    return True


def set_provider_config(config_json):
    try:
        config = json.loads(config_json)
    except (TypeError, ValueError) as error:
        raise ValueError('Provider settings must be valid JSON.') from error
    if not isinstance(config, dict):
        raise ValueError('Provider settings must be a JSON object.')

    for config_key, environment_key in _CONFIG_KEYS.items():
        value = config.get(config_key, '')
        if not isinstance(value, str):
            raise ValueError(f'{config_key} must be text.')
        value = value.strip()
        if value:
            os.environ[environment_key] = value
        else:
            os.environ.pop(environment_key, None)
    return True


def start_server(data_directory, host='127.0.0.1', port=0):
    global _server, _server_thread
    with _server_lock:
        if _server is not None:
            return _server.server_port

        os.makedirs(data_directory, exist_ok=True)
        os.environ['GEOGUARD_DB_PATH'] = os.path.join(data_directory, 'landslide.db')
        os.environ['GEOGUARD_DISABLE_ML_MODEL'] = '1'

        from werkzeug.serving import make_server
        from backend.app import app

        _server = make_server(host, int(port), app, threaded=True)
        _server_thread = threading.Thread(target=_server.serve_forever, name='GeoGuardFlask', daemon=True)
        _server_thread.start()
        return _server.server_port
