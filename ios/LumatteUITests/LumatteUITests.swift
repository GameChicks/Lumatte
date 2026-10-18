import XCTest

final class LumatteUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func waitForToast(_ text: String, timeout: TimeInterval = 30) -> Bool {
        let element = app.staticTexts[text]
        return element.waitForExistence(timeout: timeout)
    }

    private func dismissSystemAlertIfNeeded() {
        for label in ["Allow", "允许", "OK", "好", "Add", "添加"] {
            let button = app.alerts.buttons[label]
            if button.exists {
                button.tap()
                return
            }
        }
    }

    func testDarkroomImportFilmFrameExport() throws {
        let importButton = app.buttons["从相册导入"]
        XCTAssertTrue(importButton.waitForExistence(timeout: 20), app.debugDescription)
        snap("01-home")

        importButton.tap()

        let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 25), app.debugDescription)
        photo.tap()
        snap("02-photo-picker")

        let doneButton = app.buttons["完成"]
        let addButton = app.buttons["Add"]
        let addButtonZh = app.buttons["添加"]
        if doneButton.waitForExistence(timeout: 8) {
            XCTAssertTrue(doneButton.isEnabled, app.debugDescription)
            doneButton.tap()
        } else if addButton.waitForExistence(timeout: 3) {
            addButton.tap()
        } else if addButtonZh.waitForExistence(timeout: 3) {
            addButtonZh.tap()
        }
        dismissSystemAlertIfNeeded()

        XCTAssertTrue(app.staticTexts["暗房"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.buttons["更换胶卷"].waitForExistence(timeout: 20), app.debugDescription)
        snap("03-editor-default-film")

        let rendering = app.staticTexts["显影中"]
        if rendering.exists {
            let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: rendering)
            _ = XCTWaiter().wait(for: [gone], timeout: 60)
        }
        sleep(2)
        snap("04-editor-rendered")

        app.buttons["更换胶卷"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 15), app.debugDescription)
        search.tap()
        search.typeText("Portra 400")
        sleep(1)
        let portra = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Portra 400")).firstMatch
        XCTAssertTrue(portra.waitForExistence(timeout: 10), app.debugDescription)
        portra.tap()
        sleep(4)
        snap("05-portra-400")

        let frameTab = app.buttons["画框"]
        XCTAssertTrue(frameTab.waitForExistence(timeout: 10), app.debugDescription)
        frameTab.tap()
        sleep(1)
        let instant = app.buttons["拍立得"]
        XCTAssertTrue(instant.waitForExistence(timeout: 10), app.debugDescription)
        instant.tap()
        sleep(5)
        snap("06-instant-frame")

        let exportTab = app.buttons["导出"].firstMatch
        XCTAssertTrue(exportTab.waitForExistence(timeout: 10), app.debugDescription)
        exportTab.tap()
        sleep(1)

        let exportFile = app.buttons["生成文件"]
        XCTAssertTrue(exportFile.waitForExistence(timeout: 10), app.debugDescription)
        exportFile.tap()
        XCTAssertTrue(waitForToast("已生成导出文件", timeout: 60), app.debugDescription)
        snap("07-export-file")

        let saveToPhotos = app.buttons["存入相册"]
        XCTAssertTrue(saveToPhotos.waitForExistence(timeout: 10), app.debugDescription)
        saveToPhotos.tap()
        dismissSystemAlertIfNeeded()
        XCTAssertTrue(waitForToast("已保存到照片图库", timeout: 60), app.debugDescription)
        snap("08-saved-to-photos")
    }
}
