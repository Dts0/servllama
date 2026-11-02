#!/usr/bin/env python3
"""Model-free checks of the actual generated MNN 3.6.2 compatibility source."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'MNN/transformers/llm/engine/src'
CMAKE = os.environ.get('MNN_CMAKE', 'cmake')


def generate(source, output, check=True):
    return subprocess.run([
        CMAKE, f'-DMNN_PREFILL_SOURCE_DIR={source}',
        f'-DMNN_PREFILL_OUTPUT_DIR={output}', '-P',
        str(ROOT / 'scripts/cmake/mnn_prefill_compat.cmake'),
    ], check=check, capture_output=True, text=True)


class Mnn362CompatTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = Path(self.tmp.name) / 'output'
        generate(SOURCE, self.output)

    def test_cancellation_and_multimodal_patches_retained(self):
        llm = (self.output / 'llm.cpp').read_text()
        omni = (self.output / 'omni.cpp').read_text()
        self.assertEqual(llm.count('mnn_engine::cancelPrefill(mContext.get())'), 7)
        self.assertIn('mnn_engine::PrefillScope prefillScope(mContext->all_seq_len);', llm)
        self.assertIn('_Split(input_embeds, sizeSplits, mSeqLenIndex)', llm)
        self.assertIn('mConfig->is_visual() || mConfig->is_audio() || mConfig->has_talker()', llm)
        self.assertIn('outputs.empty() || mGenerateParam->outputs.empty()', llm)
        self.assertIn('int offset = mContext->all_seq_len - mnn_engine::prefillBasePosition;', omni)
        self.assertIn('values = &mPositionIds.mX;', omni)

    def test_source_is_unchanged(self):
        before = {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
                  for name in ('llm.cpp', 'omni.cpp')}
        generate(SOURCE, self.output)
        after = {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
                 for name in before}
        self.assertEqual(before, after)
        self.assertNotIn('mnn_prefill_control.hpp', (SOURCE / 'llm.cpp').read_text())

    def test_changed_upstream_anchor_is_rejected(self):
        source = Path(self.tmp.name) / 'changed'
        source.mkdir()
        for name in ('llm.cpp', 'omni.cpp'):
            shutil.copyfile(SOURCE / name, source / name)
        p = source / 'omni.cpp'
        p.write_text(p.read_text().replace('axisValue(axis, i) + offset', 'axisValue(axis, i + 1) + offset'))
        result = generate(source, self.output, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('MNN prefill source changed', result.stderr)

    def test_generated_mrope_three_and_four_axes(self):
        omni = (self.output / 'omni.cpp').read_text()
        start = omni.index('        bool relativeSpatial =')
        end = omni.index('        if (mTalker)', start)
        # Compile the actual generated position loop, rather than a duplicate
        # implementation, against tiny context/position fixtures.
        body = omni[start:end]
        source = r'''
#include <cassert>
#include <vector>
namespace mnn_engine { int prefillBasePosition = 0; }
struct Context { int all_seq_len; };
struct Vision {
    bool relative;
    bool relativeSpatialPositions() const { return relative; }
};
struct Positions { std::vector<int> mT, mH, mW, mX; };
struct Fixture {
    Context* mContext;
    Vision* mVisionProcessor;
    Positions mPositionIds;
    std::vector<int> positions(int seq_len, int axes) {
        std::vector<int> output(seq_len * axes);
        auto ptr = output.data();
''' + body + r'''
        return output;
    }
};
int main() {
    Context context{100};
    Vision vision{false};
    Fixture f{&context, &vision, {{0,1,2,3}, {10,11,12,13}, {20,21,22,23}, {30,31,32,33}}};
    mnn_engine::prefillBasePosition = 100;
    assert(f.positions(2,3) == std::vector<int>({100,101,110,111,120,121}));
    context.all_seq_len = 102;
    assert(f.positions(2,3) == std::vector<int>({102,103,112,113,122,123}));
    assert(f.positions(2,4) == std::vector<int>({102,103,112,113,122,123,132,133}));
    vision.relative = true;
    assert(f.positions(2,4) == std::vector<int>({102,103,12,13,22,23,32,33}));
    f.mPositionIds.mX.clear();
    assert(f.positions(2,4) == std::vector<int>({102,103,12,13,22,23,2,3}));
    context.all_seq_len = 2;
    mnn_engine::prefillBasePosition = 0;
    assert(f.positions(2,3) == std::vector<int>({2,3,12,13,22,23}));
    f.mVisionProcessor = nullptr;
    context.all_seq_len = 102;
    mnn_engine::prefillBasePosition = 100;
    assert(f.positions(2,4) == std::vector<int>({102,103,112,113,122,123,102,103}));
}
'''
        cpp = Path(self.tmp.name) / 'mrope.cpp'
        binary = Path(self.tmp.name) / 'mrope'
        cpp.write_text(source)
        subprocess.run([os.environ.get('CXX', 'g++'), '-std=c++17', '-Wall', '-Wextra',
                        '-Werror', str(cpp), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
