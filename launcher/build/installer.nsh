!include nsDialogs.nsh
!include LogicLib.nsh

!macro customHeader
  !define MUI_WELCOMEPAGE_TITLE "Install Polar Client"
  !define MUI_WELCOMEPAGE_TEXT "This will install Polar Client on your computer.$\r$\n$\r$\nPolar Client is a custom Minecraft launcher and client experience. Click Next to continue."
  !define MUI_FINISHPAGE_TITLE "Polar Client is ready"
  !define MUI_FINISHPAGE_TEXT "Polar Client has been installed successfully.$\r$\n$\r$\nYou can launch it now from the desktop or Start Menu shortcut."
  !define MUI_FINISHPAGE_RUN_TEXT "Launch Polar Client"
  !ifndef MUI_HEADERIMAGE
    !define MUI_HEADERIMAGE
  !endif
  !ifndef MUI_HEADERIMAGE_BITMAP
    !define MUI_HEADERIMAGE_BITMAP "${BUILD_RESOURCES_DIR}\installer-header.bmp"
  !endif
  !ifndef MUI_HEADERIMAGE_BITMAP_STRETCH
    !define MUI_HEADERIMAGE_BITMAP_STRETCH "AspectFitHeight"
  !endif
!macroend

!macro customInstall
  DetailPrint "Installing Polar Client..."
!macroend

!macro customUnInstall
  DetailPrint "Removing Polar Client..."
!macroend
