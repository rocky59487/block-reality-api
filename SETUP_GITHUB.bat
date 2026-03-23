@echo off
:: ============================================================
:: Block Reality — GitHub 推送腳本
:: 在 "Block Reality" 資料夾內雙擊執行，或在 PowerShell 執行
:: ============================================================

echo [1/3] 建立 GitHub 遠端儲存庫...
gh repo create block-reality --public --description "Block Reality API - Forge 1.20.1 Minecraft Mod" --source=. --remote=origin --push

echo [2/3] 推送所有分支...
git push origin api
git push origin fast-design
git push origin construction-intern

echo [3/3] 完成！
echo.
echo 分支列表:
git branch -a
echo.
echo GitHub 頁面: https://github.com/hankqaq/block-reality
pause
