import base64
import json
import struct
import zlib

from model.android_forest_data import FOREST_DATA


NODE_FORMAT = struct.Struct('<iii5d')
_forest_cache = None


def load_forest():
    global _forest_cache
    if _forest_cache is not None:
        return _forest_cache

    decoded = zlib.decompress(base64.b85decode(FOREST_DATA.encode('ascii')))
    metadata_json, node_data = decoded.split(b'\n', 1)
    metadata = json.loads(metadata_json)
    offset = 0
    trees = []
    for node_count in metadata['trees']:
        encoded_count = struct.unpack_from('<I', node_data, offset)[0]
        offset += 4
        if encoded_count != node_count:
            raise ValueError('Bundled Random Forest node count is inconsistent.')
        nodes = []
        for _ in range(node_count):
            nodes.append(NODE_FORMAT.unpack_from(node_data, offset))
            offset += NODE_FORMAT.size
        trees.append(nodes)
    if offset != len(node_data):
        raise ValueError('Bundled Random Forest data has unexpected trailing bytes.')
    _forest_cache = (metadata, trees)
    return _forest_cache


def predict(features):
    if len(features) != 13:
        raise ValueError('The GeoGuard Random Forest expects exactly 13 features.')
    metadata, trees = load_forest()
    scaled = [
        (float(value) - mean) / scale
        for value, mean, scale in zip(features, metadata['means'], metadata['scales'])
    ]
    class_probabilities = [0.0] * len(metadata['classes'])
    for nodes in trees:
        node_index = 0
        while nodes[node_index][0] >= 0:
            left, right, feature, threshold = nodes[node_index][:4]
            node_index = left if scaled[feature] <= threshold else right
        probabilities = nodes[node_index][4:]
        for class_index, probability in enumerate(probabilities):
            class_probabilities[class_index] += probability / len(trees)
    prediction = max(range(len(class_probabilities)), key=class_probabilities.__getitem__)
    return int(metadata['classes'][prediction]), class_probabilities
