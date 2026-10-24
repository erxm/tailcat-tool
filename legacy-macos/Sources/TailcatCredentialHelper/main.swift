import Foundation
import Security
import CryptoKit
import Darwin

// This executable has no dependency on the GUI/core. Keep protocol v1 immutable
// after installation so GUI rebuilds do not change the Keychain partition.
func respond(_ status: OSStatus, address: String? = nil) -> Never {
    var reply: [String: Any] = ["status": status, "version": 1]
    if let address { reply["address"] = address }
    if let data = try? JSONSerialization.data(withJSONObject: reply) { FileHandle.standardOutput.write(data) }
    exit(0)
}

func authorizedParent() -> Bool {
    var myself: SecCode?, staticSelf: SecStaticCode?, information: CFDictionary?
    guard SecCodeCopySelf([], &myself) == errSecSuccess, let myself,
          SecCodeCopyStaticCode(myself, [], &staticSelf) == errSecSuccess, let staticSelf,
          SecCodeCopySigningInformation(staticSelf, SecCSFlags(rawValue: kSecCSSigningInformation), &information) == errSecSuccess,
          let info = information as? [String: Any],
          let chain = info[kSecCodeInfoCertificates as String] as? [SecCertificate], let signer = chain.first else { return false }
    let fingerprint = Insecure.SHA1.hash(data: SecCertificateCopyData(signer) as Data).map { String(format: "%02x", $0) }.joined()
    var requirement: SecRequirement?, parent: SecCode?
    let rule = "identifier \"cc.coauto.tailcat-manager\" and certificate leaf = H\"\(fingerprint)\""
    let pid = getppid()
    guard SecRequirementCreateWithString(rule as CFString, [], &requirement) == errSecSuccess, let requirement,
          SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid as String: pid] as CFDictionary, [], &parent) == errSecSuccess,
          let parent, SecCodeCheckValidity(parent, SecCSFlags(rawValue: kSecCSStrictValidate), requirement) == errSecSuccess,
          getppid() == pid else { return false }
    return true
}

guard authorizedParent() else { respond(errSecAuthFailed) }
let data = FileHandle.standardInput.readDataToEndOfFile()
guard data.count <= 16384,
      let request = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
      let operation = request["operation"] as? String,
      let rawID = request["id"] as? String, let id = UUID(uuidString: rawID),
      let allowUI = request["allowUI"] as? Bool else { respond(errSecParam) }
if !allowUI { guard SecKeychainSetUserInteractionAllowed(false) == errSecSuccess else { respond(errSecInteractionNotAllowed) } }
let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "cc.coauto.tailcat-manager.connections", kSecAttrAccount as String: id.uuidString]
switch operation {
case "read":
    var match = query; match[kSecReturnData as String] = true; match[kSecMatchLimit as String] = kSecMatchLimitOne
    var item: CFTypeRef?
    let status = SecItemCopyMatching(match as CFDictionary, &item)
    if status == errSecItemNotFound { respond(errSecSuccess) }
    guard status == errSecSuccess else { respond(status) }
    guard let bytes = item as? Data, let address = String(data: bytes, encoding: .utf8) else { respond(errSecDecode) }
    respond(errSecSuccess, address: address)
case "write":
    guard let address = request["address"] as? String, address.count <= 8192,
          address.range(of: "^tc[A-Za-z0-9_-]{20,}$", options: .regularExpression) != nil else { respond(errSecParam) }
    let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: Data(address.utf8)] as CFDictionary)
    if status == errSecItemNotFound {
        var add = query; add[kSecValueData as String] = Data(address.utf8)
        respond(SecItemAdd(add as CFDictionary, nil))
    }
    respond(status)
case "delete":
    let status = SecItemDelete(query as CFDictionary)
    respond(status == errSecItemNotFound ? errSecSuccess : status)
default: respond(errSecParam)
}
