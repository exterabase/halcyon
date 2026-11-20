from Cython.Build import cythonize
from setuptools import Extension, setup

setup(
    ext_modules=cythonize(
        [Extension("halcyon_tl._security", ["halcyon_tl/_security.pyx"])],
        compiler_directives={"language_level": 3},
    )
)
