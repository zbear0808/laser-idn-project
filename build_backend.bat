@echo off
echo Setting up Visual Studio Environment...
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64

echo Building Backend...
cd backend
if not exist build mkdir build
cd build
cmake -G Ninja ..
cmake --build . --config Release

echo Done! You can run the backend with: backend\build\Release\laser_engine.exe
pause
