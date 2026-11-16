import base64
import json
import struct
import zlib
from pathlib import Path

import joblib


MODEL_DIR = Path(__file__).resolve().parent
MODEL_PATH = MODEL_DIR / 'landslide_model.pkl'
SCALER_PATH = MODEL_DIR / 'scaler.pkl'
OUTPUT_PATH = MODEL_DIR / 'android_forest_data.py'
NODE_FORMAT = struct.Struct('<iii5d')


def export_forest():
    forest = joblib.load(MODEL_PATH)
    scaler = joblib.load(SCALER_PATH)
    if list(forest.classes_) != [0, 1, 2, 3] or forest.n_features_in_ != 13:
        raise ValueError('Expected the GeoGuard four-class, 13-feature Random Forest.')

    payload = {
        'means': scaler.mean_.tolist(),
        'scales': scaler.scale_.tolist(),
        'classes': forest.classes_.tolist(),
        'trees': [],
    }
    node_data = bytearray()
    for estimator in forest.estimators_:
        tree = estimator.tree_
        payload['trees'].append(tree.node_count)
        node_data.extend(struct.pack('<I', tree.node_count))
        for index in range(tree.node_count):
            probabilities = tree.value[index][0].tolist()
            node_data.extend(NODE_FORMAT.pack(
                int(tree.children_left[index]),
                int(tree.children_right[index]),
                int(tree.feature[index]),
                float(tree.threshold[index]),
                *[float(value) for value in probabilities],
            ))

    metadata = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    encoded = base64.b85encode(zlib.compress(metadata + b'\n' + node_data, level=9)).decode('ascii')
    chunks = [encoded[index:index + 100] for index in range(0, len(encoded), 100)]
    OUTPUT_PATH.write_text(
        'FOREST_DATA = (\n' + ''.join(f"    '{chunk}'\n" for chunk in chunks) + ')\n',
        encoding='ascii',
    )
    print(f'Exported {len(forest.estimators_)} trees to {OUTPUT_PATH} ({OUTPUT_PATH.stat().st_size} bytes).')


if __name__ == '__main__':
    export_forest()
