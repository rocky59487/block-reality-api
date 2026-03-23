@echo off
:: ============================================================
:: Block Reality — GitHub 私有庫推送腳本
:: 在 "Block Reality" 資料夾內用 PowerShell 執行
:: ============================================================

echo [1/4] 建立 GitHub 私有遠端儲存庫...
gh repo create block-reality --private --description "Block Reality API - Forge 1.20.1 Minecraft Mod (Structural Physics)" --source=. --remote=origin --push

echo [2/4] 推送所有分支...
git push origin api
git push origin fast-design
git push origin construction-intern

echo [3/4] 確認分支列表...
git branch -a

echo [4/4] 完成！
echo.
echo GitHub 頁面: https://github.com/hankqaq/block-reality
pause
